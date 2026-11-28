package com.codemew.bluecall.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How see-through the glass is. SOLID turns blur off for maximum readability. */
enum class GlassStyle { CLEAR, FROSTED, SOLID }

data class ThemeSettings(
    /** A palette id from `ui.theme.Palettes`; unknown ids fall back to the phone's own colours. */
    val palette: String = DEFAULT_PALETTE,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Hue (0–360) used when [palette] is the custom colour. */
    val customHue: Float = DEFAULT_CUSTOM_HUE,
    val glass: GlassStyle = GlassStyle.FROSTED,
    /** Moving liquid backgrounds. They also pause in Battery Saver. */
    val animatedBackgrounds: Boolean = true,
) {
    companion object {
        const val DEFAULT_PALETTE = "dynamic"
        const val DEFAULT_CUSTOM_HUE = 205f
    }
}

data class AppSettings(
    /** Blank means "use the phone's own name". */
    val displayName: String = "",
    val noise: NoiseSettings = NoiseSettings(),
    val ultraLowLatency: Boolean = false,
    val autoOfflineMinutes: Int = DEFAULT_AUTO_OFFLINE_MINUTES,
    val batteryCardDismissed: Boolean = false,
    val theme: ThemeSettings = ThemeSettings(),
    /** Phone-to-phone Wi-Fi (Wi-Fi Aware) calls, on phones that support it. */
    val wifiCalling: Boolean = true,
    /** Register calls with Android's calling system (earphones, car kits, watches). */
    val systemCalling: Boolean = true,
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
    val THEME_PALETTE = stringPreferencesKey("theme_palette")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val THEME_CUSTOM_HUE = floatPreferencesKey("theme_custom_hue")
    val THEME_GLASS = stringPreferencesKey("theme_glass")
    val THEME_ANIMATED = booleanPreferencesKey("theme_animated_backgrounds")
    val WIFI_CALLING = booleanPreferencesKey("wifi_calling")
    val SYSTEM_CALLING = booleanPreferencesKey("system_calling")
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
        theme = ThemeSettings(
            palette = this[SettingsKeys.THEME_PALETTE]?.takeIf { it.isNotBlank() } ?: ThemeSettings.DEFAULT_PALETTE,
            mode = enumOrDefault(this[SettingsKeys.THEME_MODE], ThemeMode.SYSTEM),
            customHue = normalizeHue(this[SettingsKeys.THEME_CUSTOM_HUE]),
            glass = enumOrDefault(this[SettingsKeys.THEME_GLASS], GlassStyle.FROSTED),
            animatedBackgrounds = this[SettingsKeys.THEME_ANIMATED] ?: true,
        ),
        wifiCalling = this[SettingsKeys.WIFI_CALLING] ?: defaults.wifiCalling,
        systemCalling = this[SettingsKeys.SYSTEM_CALLING] ?: defaults.systemCalling,
    )
}

/** Clamps a stored hue into 0–360, falling back to the default for missing or invalid values. */
fun normalizeHue(hue: Float?): Float =
    if (hue == null || hue.isNaN()) ThemeSettings.DEFAULT_CUSTOM_HUE else hue.coerceIn(0f, 360f)

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
