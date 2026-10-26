package com.example.ftmstreadmill.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
)

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

    private companion object {
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val USE_IMPERIAL = booleanPreferencesKey("use_imperial_units")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    }
}
