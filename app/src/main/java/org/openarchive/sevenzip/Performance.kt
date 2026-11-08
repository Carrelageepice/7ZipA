package org.openarchive.sevenzip

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How hard the app may work. Saved in app preferences. */
data class PerformanceSettings(
    val cpuLimit: Int = 100,   // 25..100 (% of the time the worker may run)
    val maxTempC: Int = 0      // 0 = off, otherwise pause above this battery temperature
)

object PerformancePrefs {
    private const val PREFS = "performance"

    fun load(context: Context): PerformanceSettings =
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            PerformanceSettings(
                cpuLimit = p.getInt("cpu_limit", 100).coerceIn(25, 100),
                maxTempC = p.getInt("max_temp", 0).let { if (it in 36..50) it else 0 }
            )
        } catch (_: Exception) {
            PerformanceSettings()
        }

    fun save(context: Context, s: PerformanceSettings) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("cpu_limit", s.cpuLimit).putInt("max_temp", s.maxTempC).apply()
        } catch (_: Exception) {
            // Applies this session only.
        }
    }
}

/**
 * Slows long jobs down on purpose.
 *
 * - CPU limit: after each burst of work the worker sleeps, so that it runs
 *   about [PerformanceSettings.cpuLimit] percent of the time. The worker also
 *   gets background priority so the rest of the phone stays smooth.
 * - Heat limit: if the battery gets hotter than the chosen temperature, the
 *   job pauses ("Cooling down...") until it drops 2 degrees below it. Android
 *   does not let apps read the processor's own temperature, the battery's is
 *   the closest reading available and follows it closely.
 *
 * Both work through the progress callback the engine already calls about ten
 * times a second, so they cost nothing when switched off.
 */
object Throttle {

    /** Current battery temperature in °C, or null if unknown. */
    fun batteryTempC(context: Context): Float? {
        val i: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    /**
     * Worker threads to use when creating archives under a CPU limit (0 = let
     * 7-Zip decide). Fewer threads means less heat on top of the time-based limit.
     */
    fun suggestedThreads(context: Context): Int {
        val limit = PerformancePrefs.load(context).cpuLimit
        if (limit >= 100) return 0
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores * limit / 100).coerceIn(1, cores)
    }

    /**
     * Runs [block] with a progress callback that applies the user's limits and
     * forwards to [inner]. Call it on the thread that does the heavy work.
     */
    fun <T> run(context: Context, inner: ExtractProgress?, block: (ExtractProgress?) -> T): T {
        val s = PerformancePrefs.load(context)
        if (s.cpuLimit >= 100 && s.maxTempC == 0) return block(inner)

        val oldPriority = Process.getThreadPriority(0)
        if (s.cpuLimit < 100) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        var lastReturn = System.nanoTime()
        var lastTempCheck = 0L
        var hot = false

        val wrapped = ExtractProgress { done, total, name ->
            // The engine's last call has an empty name; let it through untouched.
            if (name.isEmpty()) return@ExtractProgress inner?.onProgress(done, total, name) ?: true

            if (inner != null && !inner.onProgress(done, total, name)) return@ExtractProgress false

            // Heat: pause while too hot.
            if (s.maxTempC > 0) {
                val now = System.nanoTime()
                if (now - lastTempCheck > 2_000_000_000L) {
                    lastTempCheck = now
                    val t = batteryTempC(context)
                    if (t != null) hot = if (hot) t > s.maxTempC - 2 else t >= s.maxTempC
                }
                while (hot) {
                    val t = batteryTempC(context)
                    val label = "Cooling down... battery ${t?.roundToInt() ?: "?"}°C"
                    if (inner != null && !inner.onProgress(done, total, label)) {
                        return@ExtractProgress false
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        return@ExtractProgress false
                    }
                    hot = t != null && t > s.maxTempC - 2
                    lastTempCheck = System.nanoTime()
                }
                lastReturn = System.nanoTime()  // the pause is not "work"
            }

            // CPU share: sleep in proportion to the work just done.
            if (s.cpuLimit < 100) {
                val workNs = System.nanoTime() - lastReturn
                var sleepMs = workNs / 1_000_000L * (100 - s.cpuLimit) / s.cpuLimit
                sleepMs = sleepMs.coerceIn(0L, 1500L)
                while (sleepMs > 0) {
                    val step = minOf(sleepMs, 250L)
                    try {
                        Thread.sleep(step)
                    } catch (_: InterruptedException) {
                        return@ExtractProgress false
                    }
                    sleepMs -= step
                    if (sleepMs > 0 && inner != null && !inner.onProgress(done, total, name)) {
                        return@ExtractProgress false
                    }
                }
                lastReturn = System.nanoTime()
            }
            true
        }

        try {
            return block(wrapped)
        } finally {
            if (s.cpuLimit < 100) Process.setThreadPriority(oldPriority)
        }
    }
}

/** The two sliders, shared by the dialog and the Extract card. Saves as you move them. */
@Composable
fun PerformanceControls() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(PerformancePrefs.load(context)) }
    val battery = remember { Throttle.batteryTempC(context) }

    fun update(s: PerformanceSettings) {
        settings = s
        PerformancePrefs.save(context, s)
    }

    Column {
        Text(
            "Big archives can keep the processor busy for a long time. " +
                "These limits make them take longer but keep the phone cooler. " +
                "They apply to extracting, creating and testing.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        Text(
            if (settings.cpuLimit >= 100) "Processor use: no limit"
            else "Processor use: about ${settings.cpuLimit}%",
            style = MaterialTheme.typography.titleSmall
        )
        Slider(
            value = settings.cpuLimit.toFloat(),
            onValueChange = { update(settings.copy(cpuLimit = (it / 5).roundToInt() * 5)) },
            valueRange = 25f..100f,
            steps = 14
        )

        Spacer(Modifier.height(4.dp))
        Text(
            if (settings.maxTempC == 0) "Pause when hot: off"
            else "Pause above ${settings.maxTempC}°C (battery)",
            style = MaterialTheme.typography.titleSmall
        )
        Slider(
            value = if (settings.maxTempC == 0) 35f else settings.maxTempC.toFloat(),
            onValueChange = {
                val v = it.roundToInt()
                update(settings.copy(maxTempC = if (v <= 35) 0 else v))
            },
            valueRange = 35f..50f,
            steps = 14
        )
        Text(
            "Slide fully left to turn off. " +
                (if (battery != null) "Battery now: ${battery.roundToInt()}°C. " else "") +
                "The job resumes 2°C below the limit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The "Performance" dialog (settings menu). */
@Composable
fun PerformanceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Performance") },
        text = { PerformanceControls() },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * A collapsible "Performance" row for the Extract card. Collapsed, it shows the
 * current limits in one line; tap it to adjust.
 */
@Composable
fun PerformanceSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // Re-read when the section is toggled so the summary reflects any change.
    val summary = remember(open) {
        val s = PerformancePrefs.load(context)
        val cpu = if (s.cpuLimit >= 100) "no CPU limit" else "CPU ${s.cpuLimit}%"
        val heat = if (s.maxTempC == 0) "heat pause off" else "pause above ${s.maxTempC}°C"
        "$cpu, $heat"
    }

    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text(
                (if (open) "Hide performance limits" else "Performance: $summary"),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (open) PerformanceControls()
    }
}
