package com.example.ftmstreadmill.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.ftmstreadmill.data.DarkMode
import com.example.ftmstreadmill.data.ThemeColors
import com.example.ftmstreadmill.data.ThemeSettings
import com.materialkolor.rememberDynamicColorScheme

/**
 * Material 3 theme. Uses the wallpaper-based dynamic colour (available on every supported API level, 31+) unless the
 * user picked a preset or custom theme, in which case the full scheme is generated from its primary and secondary seeds.
 */
@Composable
fun FtmsTheme(theme: ThemeSettings = ThemeSettings(), content: @Composable () -> Unit) {
    val dark = theme.isDark()
    MaterialTheme(colorScheme = rememberColorScheme(ThemePresets.colorsFor(theme), dark), content = content)
}

@Composable
fun ThemeSettings.isDark(): Boolean = when (darkMode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/** The scheme for [colors], or the wallpaper scheme when null. */
@Composable
fun rememberColorScheme(colors: ThemeColors?, dark: Boolean): ColorScheme {
    if (colors == null) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return rememberDynamicColorScheme(primary = Color(colors.primary), secondary = Color(colors.secondary), isDark = dark)
}
