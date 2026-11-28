package com.codemew.bluecall.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.settingsStore

    val settings: Flow<AppSettings> = store.data
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    /** The name other phones see: the user's choice, or the phone's own name. */
    fun effectiveName(settings: AppSettings): String =
        settings.displayName.trim().ifEmpty { deviceName(appContext) }

    suspend fun setDisplayName(name: String) = edit {
        it[SettingsKeys.DISPLAY_NAME] = name.take(AppSettings.MAX_DISPLAY_NAME)
    }

    suspend fun setNoise(noise: NoiseSettings) = edit {
        it[SettingsKeys.ECHO_CANCELLATION] = noise.echoCancellation
        it[SettingsKeys.NOISE_SUPPRESSION] = noise.noiseSuppression
        it[SettingsKeys.AUTO_GAIN] = noise.autoGain
        it[SettingsKeys.AI_NOISE_REDUCTION] = noise.aiNoiseReduction
        it[SettingsKeys.LOW_CUT_FILTER] = noise.lowCutFilter
    }

    suspend fun setUltraLowLatency(enabled: Boolean) = edit { it[SettingsKeys.ULTRA_LOW_LATENCY] = enabled }

    suspend fun setAutoOfflineMinutes(minutes: Int) = edit {
        it[SettingsKeys.AUTO_OFFLINE_MINUTES] = minutes.takeIf { m -> m in AppSettings.AUTO_OFFLINE_CHOICES }
            ?: AppSettings.DEFAULT_AUTO_OFFLINE_MINUTES
    }

    suspend fun setThemePalette(id: String) = edit { it[SettingsKeys.THEME_PALETTE] = id }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[SettingsKeys.THEME_MODE] = mode.name }

    suspend fun setCustomHue(hue: Float) = edit { it[SettingsKeys.THEME_CUSTOM_HUE] = normalizeHue(hue) }

    suspend fun setGlassStyle(style: GlassStyle) = edit { it[SettingsKeys.THEME_GLASS] = style.name }

    suspend fun setAnimatedBackgrounds(enabled: Boolean) = edit { it[SettingsKeys.THEME_ANIMATED] = enabled }

    suspend fun setWifiCalling(enabled: Boolean) = edit { it[SettingsKeys.WIFI_CALLING] = enabled }

    suspend fun setSystemCalling(enabled: Boolean) = edit { it[SettingsKeys.SYSTEM_CALLING] = enabled }

    suspend fun setBatteryCardDismissed(dismissed: Boolean) =
        edit { it[SettingsKeys.BATTERY_CARD_DISMISSED] = dismissed }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    companion object {
        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: Build.MODEL
    }
}
