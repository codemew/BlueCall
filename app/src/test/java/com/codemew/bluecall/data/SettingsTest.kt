package com.codemew.bluecall.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun allNoiseCancellationIsOnByDefault() {
        val noise = emptyPreferences().toAppSettings().noise
        assertTrue(noise.echoCancellation)
        assertTrue(noise.noiseSuppression)
        assertTrue(noise.autoGain)
        assertTrue(noise.aiNoiseReduction)
        assertTrue(noise.lowCutFilter)
    }

    @Test
    fun defaults() {
        val s = emptyPreferences().toAppSettings()
        assertEquals(30, s.autoOfflineMinutes)
        assertFalse(s.ultraLowLatency)
        assertEquals(20, s.frameMs)
        assertEquals("", s.displayName)
    }

    @Test
    fun eachToggleIsIndependent() {
        val prefs = mutablePreferencesOf(SettingsKeys.AI_NOISE_REDUCTION to false)
        val noise = prefs.toAppSettings().noise
        assertFalse(noise.aiNoiseReduction)
        assertTrue(noise.echoCancellation)
        assertTrue(noise.noiseSuppression)
        assertTrue(noise.autoGain)
        assertTrue(noise.lowCutFilter)
    }

    @Test
    fun invalidAutoOfflineFallsBackToDefault() {
        val prefs = mutablePreferencesOf(SettingsKeys.AUTO_OFFLINE_MINUTES to 7)
        assertEquals(30, prefs.toAppSettings().autoOfflineMinutes)
        val off = mutablePreferencesOf(SettingsKeys.AUTO_OFFLINE_MINUTES to 0)
        assertEquals(0, off.toAppSettings().autoOfflineMinutes)
    }

    @Test
    fun themeDefaults() {
        val theme = emptyPreferences().toAppSettings().theme
        assertEquals("dynamic", theme.palette)
        assertEquals(ThemeMode.SYSTEM, theme.mode)
        assertEquals(GlassStyle.FROSTED, theme.glass)
        assertTrue(theme.animatedBackgrounds)
        assertEquals(205f, theme.customHue)
    }

    @Test
    fun unknownThemeValuesFallBack() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.THEME_MODE to "PURPLE",
            SettingsKeys.THEME_GLASS to "",
            SettingsKeys.THEME_PALETTE to "",
        )
        val theme = prefs.toAppSettings().theme
        assertEquals(ThemeMode.SYSTEM, theme.mode)
        assertEquals(GlassStyle.FROSTED, theme.glass)
        assertEquals("dynamic", theme.palette)
    }

    @Test
    fun storedThemeIsRead() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.THEME_MODE to "DARK",
            SettingsKeys.THEME_GLASS to "SOLID",
            SettingsKeys.THEME_PALETTE to "sunset",
            SettingsKeys.THEME_ANIMATED to false,
            SettingsKeys.THEME_CUSTOM_HUE to 42f,
        )
        val theme = prefs.toAppSettings().theme
        assertEquals(ThemeMode.DARK, theme.mode)
        assertEquals(GlassStyle.SOLID, theme.glass)
        assertEquals("sunset", theme.palette)
        assertFalse(theme.animatedBackgrounds)
        assertEquals(42f, theme.customHue)
    }

    @Test
    fun hueIsClampedAndValidated() {
        assertEquals(360f, normalizeHue(999f))
        assertEquals(0f, normalizeHue(-5f))
        assertEquals(205f, normalizeHue(Float.NaN))
        assertEquals(205f, normalizeHue(null))
    }

    @Test
    fun ultraLowLatencyUsesTenMillisecondFrames() {
        assertEquals(10, AppSettings(ultraLowLatency = true).frameMs)
    }

    @Test
    fun wifiAndSystemCallingAreOnByDefault() {
        val s = emptyPreferences().toAppSettings()
        assertTrue(s.wifiCalling)
        assertTrue(s.systemCalling)
    }

    @Test
    fun wifiAndSystemCallingCanBeTurnedOffSeparately() {
        val noWifi = mutablePreferencesOf(SettingsKeys.WIFI_CALLING to false).toAppSettings()
        assertFalse(noWifi.wifiCalling)
        assertTrue(noWifi.systemCalling)
        val noSystem = mutablePreferencesOf(SettingsKeys.SYSTEM_CALLING to false).toAppSettings()
        assertTrue(noSystem.wifiCalling)
        assertFalse(noSystem.systemCalling)
    }
}
