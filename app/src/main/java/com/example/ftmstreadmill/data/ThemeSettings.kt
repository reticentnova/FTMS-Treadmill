package com.example.ftmstreadmill.data

enum class DarkMode { SYSTEM, LIGHT, DARK }

/** Seed colours as ARGB so the data layer stays free of Compose types. */
data class ThemeColors(val primary: Long, val secondary: Long)

data class ThemeSettings(
    /** [PRESET_DYNAMIC], a sample preset id, or [PRESET_CUSTOM]. */
    val presetId: String = PRESET_DYNAMIC,
    val custom: ThemeColors = DEFAULT_CUSTOM,
    val darkMode: DarkMode = DarkMode.SYSTEM,
) {
    companion object {
        const val PRESET_DYNAMIC = "dynamic"
        const val PRESET_CUSTOM = "custom"
        val DEFAULT_CUSTOM = ThemeColors(primary = 0xFF1E88E5, secondary = 0xFF00897B)
    }
}
