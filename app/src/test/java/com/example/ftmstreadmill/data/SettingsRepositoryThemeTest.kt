package com.example.ftmstreadmill.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.ftmstreadmill.ui.settings.parseHex
import com.example.ftmstreadmill.ui.theme.ThemePresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryThemeTest {
    private val dataStore = InMemoryPreferencesDataStore()
    private val repository = SettingsRepository(dataStore)

    private suspend fun theme() = repository.settings.first().theme

    @Test
    fun defaultsToWallpaperThemeFollowingSystem() = runTest {
        assertEquals(ThemeSettings(), theme())
        assertNull(ThemePresets.colorsFor(theme()))
    }

    @Test
    fun presetAndDarkModeRoundTrip() = runTest {
        repository.setThemePreset("forest")
        repository.setDarkMode(DarkMode.DARK)

        assertEquals("forest", theme().presetId)
        assertEquals(DarkMode.DARK, theme().darkMode)
        assertEquals(ThemePresets.all.first { it.id == "forest" }.colors, ThemePresets.colorsFor(theme()))
    }

    @Test
    fun savingCustomColorsSelectsCustomTheme() = runTest {
        val colors = ThemeColors(primary = 0xFF123456, secondary = 0xFFABCDEF)
        repository.setCustomThemeColors(colors)

        assertEquals(ThemeSettings.PRESET_CUSTOM, theme().presetId)
        assertEquals(colors, theme().custom)
        assertEquals(colors, ThemePresets.colorsFor(theme()))
    }

    @Test
    fun unknownStoredValuesFallBack() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("theme_dark_mode")] = "SEPIA"
            it[stringPreferencesKey("theme_preset")] = "removed_preset"
        }

        assertEquals(DarkMode.SYSTEM, theme().darkMode)
        assertNull(ThemePresets.colorsFor(theme()))
    }

    @Test
    fun parsesHexColors() {
        assertEquals(0xFF1E88E5, parseHex("#1e88e5"))
        assertEquals(0xFF1E88E5, parseHex("1E88E5"))
        assertNull(parseHex("#1E88E"))
        assertNull(parseHex("#GGGGGG"))
    }
}
