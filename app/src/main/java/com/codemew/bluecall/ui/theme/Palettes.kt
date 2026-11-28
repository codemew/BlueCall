package com.codemew.bluecall.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * A colour theme: [seed] generates the app's Material colour scheme and
 * [liquid] are the four colours that flow in the liquid backgrounds.
 */
data class LiquidPalette(val id: String, val name: String, val seed: Color, val liquid: List<Color>)

object Palettes {
    /** The phone's own colours (One UI / Material You, from the wallpaper). */
    const val DYNAMIC = "dynamic"

    /** Any hue the user picks. */
    const val CUSTOM = "custom"

    val presets: List<LiquidPalette> = listOf(
        palette("ocean", "Ocean", 0xFF1E88E5, 0xFF0A2463, 0xFF1E88E5, 0xFF00C2D1, 0xFF6C2BD9),
        palette("aurora", "Aurora", 0xFF00BFA5, 0xFF00C9A7, 0xFF00B4D8, 0xFF7B2FF7, 0xFFF72585),
        palette("sunset", "Sunset", 0xFFFF6D3A, 0xFFFF5E3A, 0xFFFFB627, 0xFFE8175D, 0xFF5B2A86),
        palette("lavender", "Lavender", 0xFF9575CD, 0xFF7F5AF0, 0xFFC3A6FF, 0xFFFF8FC7, 0xFF6EC1E4),
        palette("rose", "Rose", 0xFFE91E63, 0xFFF72585, 0xFFFF8FAB, 0xFFB5179E, 0xFFFFB38A),
        palette("forest", "Forest", 0xFF2E7D32, 0xFF1B4332, 0xFF40916C, 0xFF95D5B2, 0xFFD4A373),
        palette("mint", "Mint", 0xFF26A69A, 0xFF00BFA6, 0xFF64FFDA, 0xFF00B4D8, 0xFFB9FBC0),
        palette("citrus", "Citrus", 0xFFF9A825, 0xFFFFD60A, 0xFFFF8800, 0xFF9EF01A, 0xFFFF5400),
        palette("ember", "Ember", 0xFFD84315, 0xFF9D0208, 0xFFE85D04, 0xFFFFBA08, 0xFF370617),
        palette("coral", "Coral", 0xFFFF7F50, 0xFFFF7F50, 0xFFFF4F79, 0xFFFFD1BA, 0xFF00B2CA),
        palette("midnight", "Midnight", 0xFF3949AB, 0xFF03045E, 0xFF3A0CA3, 0xFF4361EE, 0xFF4CC9F0),
        palette("graphite", "Graphite", 0xFF607D8B, 0xFF1F2933, 0xFF52606D, 0xFF9AA5B1, 0xFF3E4C59),
    )

    /** Every choice shown in Settings, in display order. */
    val choiceIds: List<String> = listOf(DYNAMIC) + presets.map { it.id } + CUSTOM

    fun preset(id: String): LiquidPalette? = presets.firstOrNull { it.id == id }

    /** The id to use for a stored value: unknown ids fall back to the phone's own colours. */
    fun resolveId(id: String): String = if (id == DYNAMIC || id == CUSTOM || preset(id) != null) id else DYNAMIC

    fun displayName(id: String): String = when (id) {
        DYNAMIC -> "Your phone"
        CUSTOM -> "Custom"
        else -> preset(id)?.name ?: "Your phone"
    }

    /** A palette from a single hue: neighbouring hues for the flow plus one complementary accent. */
    fun custom(hue: Float): LiquidPalette {
        val h = hue.toDouble()
        return LiquidPalette(
            id = CUSTOM,
            name = "Custom",
            seed = SchemeGenerator.vivid(h, 0.62, 0.16),
            liquid = listOf(
                SchemeGenerator.vivid(h - 35, 0.42, 0.16),
                SchemeGenerator.vivid(h, 0.62, 0.20),
                SchemeGenerator.vivid(h + 35, 0.74, 0.16),
                SchemeGenerator.vivid(h + 180, 0.66, 0.14),
            ),
        )
    }

    /** Liquid colours from a dynamic scheme: same hues, made vivid enough to flow nicely. */
    fun liquidFrom(colors: List<Color>): List<Color> {
        val lightness = listOf(0.45, 0.62, 0.72, 0.55)
        return colors.take(4).mapIndexed { i, c ->
            val o = SchemeGenerator.toOklch(c.toArgb())
            SchemeGenerator.vivid(o.h, lightness[i], maxOf(o.c, 0.12))
        }
    }

    private fun palette(id: String, name: String, seed: Long, vararg liquid: Long) =
        LiquidPalette(id, name, Color(seed), liquid.map { Color(it) })
}
