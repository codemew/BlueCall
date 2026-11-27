package com.codemew.bluecall.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Each noise-cancellation stage can be switched off on its own. All are on by default. */
data class NoiseSettings(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGain: Boolean = true,
    val aiNoiseReduction: Boolean = true,
    val lowCutFilter: Boolean = true,
)

data class AppSettings(
    /** Blank means "use the phone's own name". */
    val displayName: String = "",
    val noise: NoiseSettings = NoiseSettings(),
    val ultraLowLatency: Boolean = false,
    val autoOfflineMinutes: Int = DEFAULT_AUTO_OFFLINE_MINUTES,
    val batteryCardDismissed: Boolean = false,
) {
    /** Opus frame duration: 10 ms for lowest latency, 20 ms for the best battery/latency balance. */
    val frameMs: Int get() = if (ultraLowLatency) 10 else 20

    companion object {
        const val DEFAULT_AUTO_OFFLINE_MINUTES = 30
        val AUTO_OFFLINE_CHOICES = listOf(0, 15, 30, 60)
        const val MAX_DISPLAY_NAME = 32
    }
}

object SettingsKeys {
    val DISPLAY_NAME = stringPreferencesKey("display_name")
    val ECHO_CANCELLATION = booleanPreferencesKey("noise_echo_cancellation")
    val NOISE_SUPPRESSION = booleanPreferencesKey("noise_suppression")
    val AUTO_GAIN = booleanPreferencesKey("noise_auto_gain")
    val AI_NOISE_REDUCTION = booleanPreferencesKey("noise_ai_reduction")
    val LOW_CUT_FILTER = booleanPreferencesKey("noise_low_cut")
    val ULTRA_LOW_LATENCY = booleanPreferencesKey("ultra_low_latency")
    val AUTO_OFFLINE_MINUTES = intPreferencesKey("auto_offline_minutes")
    val BATTERY_CARD_DISMISSED = booleanPreferencesKey("battery_card_dismissed")
}

fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        displayName = this[SettingsKeys.DISPLAY_NAME] ?: defaults.displayName,
        noise = NoiseSettings(
            echoCancellation = this[SettingsKeys.ECHO_CANCELLATION] ?: defaults.noise.echoCancellation,
            noiseSuppression = this[SettingsKeys.NOISE_SUPPRESSION] ?: defaults.noise.noiseSuppression,
            autoGain = this[SettingsKeys.AUTO_GAIN] ?: defaults.noise.autoGain,
            aiNoiseReduction = this[SettingsKeys.AI_NOISE_REDUCTION] ?: defaults.noise.aiNoiseReduction,
            lowCutFilter = this[SettingsKeys.LOW_CUT_FILTER] ?: defaults.noise.lowCutFilter,
        ),
        ultraLowLatency = this[SettingsKeys.ULTRA_LOW_LATENCY] ?: defaults.ultraLowLatency,
        autoOfflineMinutes = (this[SettingsKeys.AUTO_OFFLINE_MINUTES] ?: defaults.autoOfflineMinutes)
            .takeIf { it in AppSettings.AUTO_OFFLINE_CHOICES } ?: defaults.autoOfflineMinutes,
        batteryCardDismissed = this[SettingsKeys.BATTERY_CARD_DISMISSED] ?: defaults.batteryCardDismissed,
    )
}
