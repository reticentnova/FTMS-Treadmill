package com.example.ftmstreadmill.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class Settings(
    val lastDevice: DeviceInfo? = null,
    val useImperialUnits: Boolean = defaultImperial(),
    val autoReconnect: Boolean = true,
    val workout: WorkoutSettings = WorkoutSettings(),
    val theme: ThemeSettings = ThemeSettings(),
)

/** A default warmup or cooldown. Speed is stored in km/h like all data-layer speeds. */
data class SegmentDefaults(val durationSec: Int, val speedKmh: Double, val inclinePercent: Double)

data class WorkoutSettings(
    val warmup: SegmentDefaults = DEFAULT_WARMUP,
    val cooldown: SegmentDefaults = DEFAULT_WARMUP,
    val cueSound: Boolean = true,
    val cueVoice: Boolean = true,
    val cueVibrate: Boolean = true,
    val healthConnectAutoExport: Boolean = false,
) {
    companion object {
        /** 2 mph, 0 %, 5 minutes. */
        val DEFAULT_WARMUP = SegmentDefaults(durationSec = 300, speedKmh = 2 * 1.609344, inclinePercent = 0.0)
    }
}

/** Imperial by default only in locales that use miles on the road. */
internal fun defaultImperial(): Boolean = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val address = prefs[LAST_DEVICE_ADDRESS]
            Settings(
                lastDevice = address?.let { DeviceInfo(it, prefs[LAST_DEVICE_NAME]) },
                useImperialUnits = prefs[USE_IMPERIAL] ?: defaultImperial(),
                autoReconnect = prefs[AUTO_RECONNECT] ?: true,
                workout = WorkoutSettings(
                    warmup = prefs.segmentDefaults(WARMUP_SEC, WARMUP_SPEED, WARMUP_INCLINE),
                    cooldown = prefs.segmentDefaults(COOLDOWN_SEC, COOLDOWN_SPEED, COOLDOWN_INCLINE),
                    cueSound = prefs[CUE_SOUND] ?: true,
                    cueVoice = prefs[CUE_VOICE] ?: true,
                    cueVibrate = prefs[CUE_VIBRATE] ?: true,
                    healthConnectAutoExport = prefs[HEALTH_CONNECT_AUTO] ?: false,
                ),
                theme = ThemeSettings(
                    presetId = prefs[THEME_PRESET] ?: ThemeSettings.PRESET_DYNAMIC,
                    custom = ThemeColors(
                        primary = prefs[THEME_CUSTOM_PRIMARY] ?: ThemeSettings.DEFAULT_CUSTOM.primary,
                        secondary = prefs[THEME_CUSTOM_SECONDARY] ?: ThemeSettings.DEFAULT_CUSTOM.secondary,
                    ),
                    darkMode = prefs[THEME_DARK_MODE]?.let { name -> DarkMode.entries.firstOrNull { it.name == name } }
                        ?: DarkMode.SYSTEM,
                ),
            )
        }

    suspend fun setLastDevice(device: DeviceInfo) {
        dataStore.edit {
            it[LAST_DEVICE_ADDRESS] = device.address
            if (device.name != null) it[LAST_DEVICE_NAME] = device.name else it.remove(LAST_DEVICE_NAME)
        }
    }

    suspend fun clearLastDevice() {
        dataStore.edit {
            it.remove(LAST_DEVICE_ADDRESS)
            it.remove(LAST_DEVICE_NAME)
        }
    }

    suspend fun setUseImperialUnits(value: Boolean) {
        dataStore.edit { it[USE_IMPERIAL] = value }
    }

    suspend fun setAutoReconnect(value: Boolean) {
        dataStore.edit { it[AUTO_RECONNECT] = value }
    }

    suspend fun setWarmupDefaults(value: SegmentDefaults) = setSegmentDefaults(value, WARMUP_SEC, WARMUP_SPEED, WARMUP_INCLINE)

    suspend fun setCooldownDefaults(value: SegmentDefaults) =
        setSegmentDefaults(value, COOLDOWN_SEC, COOLDOWN_SPEED, COOLDOWN_INCLINE)

    suspend fun setCueSound(value: Boolean) {
        dataStore.edit { it[CUE_SOUND] = value }
    }

    suspend fun setCueVoice(value: Boolean) {
        dataStore.edit { it[CUE_VOICE] = value }
    }

    suspend fun setCueVibrate(value: Boolean) {
        dataStore.edit { it[CUE_VIBRATE] = value }
    }

    suspend fun setHealthConnectAutoExport(value: Boolean) {
        dataStore.edit { it[HEALTH_CONNECT_AUTO] = value }
    }

    suspend fun setThemePreset(id: String) {
        dataStore.edit { it[THEME_PRESET] = id }
    }

    /** Saves the custom colours and switches to the custom theme. */
    suspend fun setCustomThemeColors(value: ThemeColors) {
        dataStore.edit {
            it[THEME_CUSTOM_PRIMARY] = value.primary
            it[THEME_CUSTOM_SECONDARY] = value.secondary
            it[THEME_PRESET] = ThemeSettings.PRESET_CUSTOM
        }
    }

    suspend fun setDarkMode(value: DarkMode) {
        dataStore.edit { it[THEME_DARK_MODE] = value.name }
    }

    private suspend fun setSegmentDefaults(
        value: SegmentDefaults,
        sec: Preferences.Key<Int>,
        speed: Preferences.Key<Double>,
        incline: Preferences.Key<Double>,
    ) {
        dataStore.edit {
            it[sec] = value.durationSec
            it[speed] = value.speedKmh
            it[incline] = value.inclinePercent
        }
    }

    private fun Preferences.segmentDefaults(
        sec: Preferences.Key<Int>,
        speed: Preferences.Key<Double>,
        incline: Preferences.Key<Double>,
    ): SegmentDefaults {
        val default = WorkoutSettings.DEFAULT_WARMUP
        return SegmentDefaults(
            durationSec = this[sec] ?: default.durationSec,
            speedKmh = this[speed] ?: default.speedKmh,
            inclinePercent = this[incline] ?: default.inclinePercent,
        )
    }

    private companion object {
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val USE_IMPERIAL = booleanPreferencesKey("use_imperial_units")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val WARMUP_SEC = intPreferencesKey("warmup_sec")
        val WARMUP_SPEED = doublePreferencesKey("warmup_speed_kmh")
        val WARMUP_INCLINE = doublePreferencesKey("warmup_incline")
        val COOLDOWN_SEC = intPreferencesKey("cooldown_sec")
        val COOLDOWN_SPEED = doublePreferencesKey("cooldown_speed_kmh")
        val COOLDOWN_INCLINE = doublePreferencesKey("cooldown_incline")
        val CUE_SOUND = booleanPreferencesKey("cue_sound")
        val CUE_VOICE = booleanPreferencesKey("cue_voice")
        val CUE_VIBRATE = booleanPreferencesKey("cue_vibrate")
        val HEALTH_CONNECT_AUTO = booleanPreferencesKey("health_connect_auto_export")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val THEME_CUSTOM_PRIMARY = longPreferencesKey("theme_custom_primary")
        val THEME_CUSTOM_SECONDARY = longPreferencesKey("theme_custom_secondary")
        val THEME_DARK_MODE = stringPreferencesKey("theme_dark_mode")
    }
}
