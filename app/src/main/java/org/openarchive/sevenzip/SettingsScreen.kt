package org.openarchive.sevenzip

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Small app-wide switches saved in preferences. */
object AppPrefs {
    private const val PREFS = "settings"
    private const val KEY_ADVANCED = "advanced_options"

    fun advancedOptions(context: Context): Boolean =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ADVANCED, false)
        } catch (_: Exception) {
            false
        }

    fun setAdvancedOptions(context: Context, on: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ADVANCED, on).apply()
        } catch (_: Exception) {
            // Applies this session only.
        }
    }
}

/**
 * The Settings page. Shown instead of the main screen; [onBack] returns to it
 * (the caller should also wire the system back button to it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    advancedOptions: Boolean,
    onAdvancedChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                SettingsSection("Appearance") {
                    for (m in ThemeMode.values()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = themeMode == m,
                                    onClick = { onThemeChange(m) },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(selected = themeMode == m, onClick = null)
                            Text(m.label, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    Text(
                        "\"Follow system\" is light by day and grey when your phone is in dark mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsSection("Creating archives") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Advanced options", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Show compression level, algorithm, solid archive, dictionary " +
                                    "size and CPU threads when creating an archive. " +
                                    "Off keeps things simple: sensible defaults are used.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = advancedOptions,
                            onCheckedChange = {
                                AppPrefs.setAdvancedOptions(context, it)
                                onAdvancedChange(it)
                            },
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }

            item {
                SettingsSection("Performance") { PerformanceControls() }
            }

            item {
                SettingsSection("Storage") { AllFilesAccessToggle() }
            }

            item {
                SettingsSection("About") {
                    Text("7Zip " + appVersionName(context), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Based on the 7-Zip engine (LGPL). The RAR decoder can extract only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
