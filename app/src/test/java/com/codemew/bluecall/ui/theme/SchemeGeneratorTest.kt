package com.codemew.bluecall.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SchemeGeneratorTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private val seeds: List<Pair<String, Color>> =
        Palettes.presets.map { it.name to it.seed } +
            (0 until 360 step 30).map { "hue $it" to Palettes.custom(it.toFloat()).seed }

    private fun ColorScheme.checkReadable(label: String) {
        val pairs = listOf(
            "onPrimary/primary" to (onPrimary to primary) to 4.5,
            "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer) to 4.5,
            "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer) to 4.5,
            "onTertiary/tertiary" to (onTertiary to tertiary) to 4.5,
            "onSurface/surface" to (onSurface to surface) to 7.0,
            "onSurfaceVariant/surface" to (onSurfaceVariant to surface) to 4.5,
            "onError/error" to (onError to error) to 4.5,
        )
        for ((named, minimum) in pairs) {
            val (name, colors) = named
            val ratio = contrast(colors.first, colors.second)
            assertTrue("$label $name contrast %.2f < $minimum".format(ratio), ratio >= minimum)
        }
    }

    @Test
    fun everyPaletteIsReadableInLightAndDark() {
        for ((label, seed) in seeds) {
            SchemeGenerator.scheme(seed, dark = false).checkReadable("$label light")
            SchemeGenerator.scheme(seed, dark = true).checkReadable("$label dark")
        }
    }

    @Test
    fun colorsAreOpaqueSrgb() {
        for ((_, seed) in seeds) {
            for (dark in listOf(false, true)) {
                val s = SchemeGenerator.scheme(seed, dark)
                listOf(s.primary, s.surface, s.onSurface, s.tertiary, s.outline).forEach {
                    assertEquals(0xFF, (it.toArgb() ushr 24) and 0xFF)
                }
            }
        }
    }

    @Test
    fun toneHitsTargetLuminance() {
        for (tone in listOf(10, 40, 50, 80, 90)) {
            val c = Color(SchemeGenerator.colorAtTone(250.0, 0.12, tone.toDouble()))
            val expected = SchemeGenerator.toneToY(tone.toDouble())
            assertTrue("tone $tone", abs(c.luminance() - expected) < 0.01)
        }
    }

    @Test
    fun primaryFollowsTheSeedHue() {
        val hues = Palettes.presets.filter { it.id != "graphite" }.map {
            SchemeGenerator.toOklch(SchemeGenerator.scheme(it.seed, dark = false).primary.toArgb()).h to
                SchemeGenerator.toOklch(it.seed.toArgb()).h
        }
        for ((primaryHue, seedHue) in hues) {
            val diff = abs(((primaryHue - seedHue + 540) % 360) - 180)
            assertTrue("hue drifted by $diff", diff < 12)
        }
    }

    @Test
    fun presetsLookDistinct() {
        val primaries = Palettes.presets.map { SchemeGenerator.scheme(it.seed, dark = false).primary.toArgb() }
        assertEquals(primaries.size, primaries.toSet().size)
    }
}
