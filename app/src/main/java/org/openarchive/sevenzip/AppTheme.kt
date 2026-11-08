package org.openarchive.sevenzip

import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** What the user picked in the settings menu. */
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),   // light when the phone is light, grey when it is dark
    LIGHT("Light"),
    GREY("Grey"),
    AMOLED("AMOLED black")
}

object ThemePrefs {
    private const val PREFS = "settings"
    private const val KEY = "theme_mode"

    fun load(context: Context): ThemeMode =
        try {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            ThemeMode.values().firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }

    fun save(context: Context, mode: ThemeMode) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name).apply()
        } catch (_: Exception) {
            // The choice still applies for this session.
        }
    }
}

private val GreyColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF3F4A5E),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF444B5A),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF574059),
    onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF2B2B2D),
    onBackground = Color(0xFFE4E4E6),
    surface = Color(0xFF2B2B2D),
    onSurface = Color(0xFFE4E4E6),
    surfaceVariant = Color(0xFF47474A),
    onSurfaceVariant = Color(0xFFC7C7CA),
    surfaceContainerLowest = Color(0xFF232325),
    surfaceContainerLow = Color(0xFF2F2F31),
    surfaceContainer = Color(0xFF353537),
    surfaceContainerHigh = Color(0xFF3F3F41),
    surfaceContainerHighest = Color(0xFF4A4A4C),
    outline = Color(0xFF919194),
    outlineVariant = Color(0xFF47474A)
)

private val AmoledColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1B2A44),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF232A38),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF3A2640),
    onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color(0xFFE6E6E6),
    surface = Color.Black,
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFC4C4C4),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFF2A2A2A)
)

/**
 * Wraps the app in the chosen theme. Also colours the window behind the app
 * and switches the status/navigation bar icons so nothing flashes white.
 */
@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val scheme = when (mode) {
        ThemeMode.LIGHT -> lightColorScheme()
        ThemeMode.GREY -> GreyColors
        ThemeMode.AMOLED -> AmoledColors
        ThemeMode.SYSTEM -> if (systemDark) GreyColors else lightColorScheme()
    }
    val dark = scheme === GreyColors || scheme === AmoledColors

    val view: View = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                window.decorView.setBackgroundColor(scheme.background.toArgb())
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

/** Version shown on the Settings page. */
fun appVersionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
