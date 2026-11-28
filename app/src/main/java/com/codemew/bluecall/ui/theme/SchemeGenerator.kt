package com.codemew.bluecall.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Builds full Material 3 light and dark colour schemes from one seed colour.
 *
 * Colours are made in OKLCH (perceptual lightness, chroma, hue). Each role
 * uses a Material "tone" (CIE L*, 0–100), and the colour is solved to hit that
 * tone's luminance exactly, with chroma reduced only as far as needed to stay
 * inside sRGB. Matching tones therefore give the same contrast ratios as
 * Material's own palettes (e.g. primary T40 on onPrimary T100 is about 6.5:1).
 */
object SchemeGenerator {

    data class Oklch(val l: Double, val c: Double, val h: Double)

    fun scheme(seed: Color, dark: Boolean): ColorScheme {
        val s = toOklch(seed.toArgb())
        val chroma = s.c.coerceIn(0.02, 0.16)
        val primary = Palette(s.h, chroma)
        val secondary = Palette(s.h, chroma * 0.35)
        val tertiary = Palette(s.h + 60.0, max(chroma * 0.6, 0.03))
        val neutral = Palette(s.h, min(chroma * 0.08, 0.012))
        val neutralVariant = Palette(s.h, min(chroma * 0.16, 0.025))
        val error = Palette(25.0, 0.17)

        return if (!dark) {
            lightColorScheme(
                primary = primary[40], onPrimary = primary[100],
                primaryContainer = primary[90], onPrimaryContainer = primary[10],
                inversePrimary = primary[80],
                secondary = secondary[40], onSecondary = secondary[100],
                secondaryContainer = secondary[90], onSecondaryContainer = secondary[10],
                tertiary = tertiary[40], onTertiary = tertiary[100],
                tertiaryContainer = tertiary[90], onTertiaryContainer = tertiary[10],
                error = error[40], onError = error[100], errorContainer = error[90], onErrorContainer = error[10],
                background = neutral[98], onBackground = neutral[10],
                surface = neutral[98], onSurface = neutral[10],
                surfaceVariant = neutralVariant[90], onSurfaceVariant = neutralVariant[30],
                surfaceTint = primary[40],
                inverseSurface = neutral[20], inverseOnSurface = neutral[95],
                outline = neutralVariant[50], outlineVariant = neutralVariant[80],
                scrim = Color.Black,
                surfaceBright = neutral[98], surfaceDim = neutral[87],
                surfaceContainerLowest = neutral[100], surfaceContainerLow = neutral[96],
                surfaceContainer = neutral[94], surfaceContainerHigh = neutral[92],
                surfaceContainerHighest = neutral[90],
            )
        } else {
            darkColorScheme(
                primary = primary[80], onPrimary = primary[20],
                primaryContainer = primary[30], onPrimaryContainer = primary[90],
                inversePrimary = primary[40],
                secondary = secondary[80], onSecondary = secondary[20],
                secondaryContainer = secondary[30], onSecondaryContainer = secondary[90],
                tertiary = tertiary[80], onTertiary = tertiary[20],
                tertiaryContainer = tertiary[30], onTertiaryContainer = tertiary[90],
                error = error[80], onError = error[20], errorContainer = error[30], onErrorContainer = error[90],
                background = neutral[6], onBackground = neutral[90],
                surface = neutral[6], onSurface = neutral[90],
                surfaceVariant = neutralVariant[30], onSurfaceVariant = neutralVariant[80],
                surfaceTint = primary[80],
                inverseSurface = neutral[90], inverseOnSurface = neutral[20],
                outline = neutralVariant[60], outlineVariant = neutralVariant[30],
                scrim = Color.Black,
                surfaceBright = neutral[24], surfaceDim = neutral[6],
                surfaceContainerLowest = neutral[4], surfaceContainerLow = neutral[10],
                surfaceContainer = neutral[12], surfaceContainerHigh = neutral[17],
                surfaceContainerHighest = neutral[22],
            )
        }
    }

    /** A hue/chroma pair whose colours are looked up by tone. */
    class Palette(private val hue: Double, private val chroma: Double) {
        private val cache = HashMap<Int, Color>()
        operator fun get(tone: Int): Color = cache.getOrPut(tone) { Color(colorAtTone(hue, chroma, tone.toDouble())) }
    }

    /** A vivid sRGB colour at OKLCH lightness [l], with as much of [chroma] as fits. */
    fun vivid(hue: Double, l: Double, chroma: Double): Color {
        var lo = 0.0
        var hi = chroma
        var best = linearFromOklch(l, 0.0, hue)
        repeat(18) {
            val mid = (lo + hi) / 2
            val rgb = linearFromOklch(l, mid, hue)
            if (inGamut(rgb)) {
                best = rgb
                lo = mid
            } else {
                hi = mid
            }
        }
        if (inGamut(linearFromOklch(l, chroma, hue))) best = linearFromOklch(l, chroma, hue)
        return Color(encode(best))
    }

    /** sRGB colour with the given OKLCH hue and (at most) chroma, at Material tone [tone]. */
    fun colorAtTone(hue: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0.0) return 0xFF000000.toInt()
        if (tone >= 100.0) return 0xFFFFFFFF.toInt()
        val targetY = toneToY(tone)
        // Largest chroma (up to the request) whose luminance-matched colour is inside sRGB.
        var lo = 0.0
        var hi = chroma
        var best = solveForLuminance(hue, 0.0, targetY)
        val full = solveForLuminance(hue, chroma, targetY)
        if (inGamut(full)) return encode(full)
        repeat(16) {
            val mid = (lo + hi) / 2
            val rgb = solveForLuminance(hue, mid, targetY)
            if (inGamut(rgb)) {
                best = rgb
                lo = mid
            } else {
                hi = mid
            }
        }
        return encode(best)
    }

    /** Finds OKLab lightness so the colour at (hue, chroma) has relative luminance [targetY]. */
    private fun solveForLuminance(hue: Double, chroma: Double, targetY: Double): DoubleArray {
        var lo = 0.0
        var hi = 1.0
        var rgb = linearFromOklch(0.5, chroma, hue)
        repeat(24) {
            val mid = (lo + hi) / 2
            rgb = linearFromOklch(mid, chroma, hue)
            if (luminance(rgb) < targetY) lo = mid else hi = mid
        }
        return rgb
    }

    fun toOklch(argb: Int): Oklch {
        val r = srgbToLinear(((argb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((argb shr 8) and 0xFF) / 255.0)
        val b = srgbToLinear((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val lab = doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
        val c = hypot(lab[1], lab[2])
        var h = Math.toDegrees(atan2(lab[2], lab[1]))
        if (h < 0) h += 360.0
        return Oklch(lab[0], c, h)
    }

    private fun linearFromOklch(lightness: Double, chroma: Double, hue: Double): DoubleArray {
        val rad = Math.toRadians(hue)
        val a = chroma * cos(rad)
        val b = chroma * sin(rad)
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -1e-4 && it <= 1.0 + 1e-4 }

    private fun luminance(rgb: DoubleArray) = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    /** CIE L* (Material tone) to relative luminance. */
    fun toneToY(tone: Double): Double {
        val fy = (tone + 16.0) / 116.0
        return if (tone > 8.0) fy * fy * fy else tone / 903.2962962
    }

    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    private fun encode(rgb: DoubleArray): Int {
        fun ch(v: Double) = (linearToSrgb(v.coerceIn(0.0, 1.0)) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(rgb[0]) shl 16) or (ch(rgb[1]) shl 8) or ch(rgb[2])
    }
}
