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
    fun ultraLowLatencyUsesTenMillisecondFrames() {
        assertEquals(10, AppSettings(ultraLowLatency = true).frameMs)
    }
}
