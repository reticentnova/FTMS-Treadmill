package com.example.ftmstreadmill.ui.theme

import androidx.annotation.StringRes
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.ThemeColors
import com.example.ftmstreadmill.data.ThemeSettings

/** A selectable theme. [colors] is null for the wallpaper-based dynamic scheme. */
data class ThemePreset(val id: String, @StringRes val name: Int, val colors: ThemeColors?)

object ThemePresets {
    val all = listOf(
        ThemePreset(ThemeSettings.PRESET_DYNAMIC, R.string.theme_dynamic, null),
        ThemePreset("ocean", R.string.theme_ocean, ThemeColors(0xFF1565C0, 0xFF00838F)),
        ThemePreset("forest", R.string.theme_forest, ThemeColors(0xFF2E7D32, 0xFF9E9D24)),
        ThemePreset("sunset", R.string.theme_sunset, ThemeColors(0xFFEF6C00, 0xFFD81B60)),
        ThemePreset("grape", R.string.theme_grape, ThemeColors(0xFF6A1B9A, 0xFFFFA000)),
        ThemePreset("graphite", R.string.theme_graphite, ThemeColors(0xFF455A64, 0xFF00ACC1)),
        ThemePreset("cherry", R.string.theme_cherry, ThemeColors(0xFFC62828, 0xFFFFB300)),
    )

    /** Seed colours for [theme], or null when the dynamic wallpaper scheme applies. Unknown ids fall back to dynamic. */
    fun colorsFor(theme: ThemeSettings): ThemeColors? = when (theme.presetId) {
        ThemeSettings.PRESET_CUSTOM -> theme.custom
        else -> all.firstOrNull { it.id == theme.presetId }?.colors
    }

    /** Swatch choices offered when building a custom theme. */
    val palette = listOf(
        0xFFD32F2F, 0xFFC2185B, 0xFF7B1FA2, 0xFF512DA8,
        0xFF303F9F, 0xFF1976D2, 0xFF0288D1, 0xFF0097A7,
        0xFF00796B, 0xFF388E3C, 0xFF689F38, 0xFFAFB42B,
        0xFFFBC02D, 0xFFFFA000, 0xFFF57C00, 0xFF5D4037,
    )
}
