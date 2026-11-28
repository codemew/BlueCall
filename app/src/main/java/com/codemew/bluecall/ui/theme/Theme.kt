package com.codemew.bluecall.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.codemew.bluecall.data.GlassStyle
import com.codemew.bluecall.data.ThemeMode
import com.codemew.bluecall.data.ThemeSettings

val CallGreen = Color(0xFF22C55E)
val HangUpRed = Color(0xFFEF4444)

/** Everything the glass and liquid components need to know about the current look. */
@Immutable
data class BlueCallLook(
    val dark: Boolean,
    val liquid: List<Color>,
    val glass: GlassStyle,
    /** The user's "Animated backgrounds" setting (Battery Saver is checked separately). */
    val motion: Boolean,
)

val LocalBlueCallLook = staticCompositionLocalOf {
    BlueCallLook(dark = false, liquid = Palettes.presets.first().liquid, glass = GlassStyle.FROSTED, motion = true)
}

/**
 * BlueCall's theme: the chosen palette (or the phone's own colours), light or
 * dark mode, and glass style. Switching palette morphs every colour smoothly.
 *
 * @param forceDark always dark, used by the call screen for white text over vivid colours.
 */
@Composable
fun BlueCallTheme(
    theme: ThemeSettings = ThemeSettings(),
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = forceDark || when (theme.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val id = Palettes.resolveId(theme.palette)
    val (scheme, liquid) = remember(id, theme.customHue, dark, context) {
        when (id) {
            Palettes.DYNAMIC -> {
                val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                s to Palettes.liquidFrom(listOf(s.primary, s.tertiary, s.secondary, s.primaryContainer))
            }

            Palettes.CUSTOM -> {
                val p = Palettes.custom(theme.customHue)
                SchemeGenerator.scheme(p.seed, dark) to p.liquid
            }

            else -> {
                val p = Palettes.preset(id) ?: Palettes.presets.first()
                SchemeGenerator.scheme(p.seed, dark) to p.liquid
            }
        }
    }
    val animatedLiquid = liquid.map { animateColorAsState(it, morph, label = "liquid").value }
    val look = BlueCallLook(dark, animatedLiquid, theme.glass, theme.animatedBackgrounds)
    CompositionLocalProvider(LocalBlueCallLook provides look) {
        MaterialTheme(colorScheme = scheme.animated(), content = content)
    }
}

private val morph: AnimationSpec<Color> = tween(durationMillis = 450)

/** Animates the colour roles BlueCall uses, so palette and mode changes cross-fade. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun a(c: Color) = animateColorAsState(c, morph, label = "scheme").value
    return copy(
        primary = a(primary),
        onPrimary = a(onPrimary),
        primaryContainer = a(primaryContainer),
        onPrimaryContainer = a(onPrimaryContainer),
        secondary = a(secondary),
        onSecondary = a(onSecondary),
        secondaryContainer = a(secondaryContainer),
        onSecondaryContainer = a(onSecondaryContainer),
        tertiary = a(tertiary),
        onTertiary = a(onTertiary),
        tertiaryContainer = a(tertiaryContainer),
        onTertiaryContainer = a(onTertiaryContainer),
        background = a(background),
        onBackground = a(onBackground),
        surface = a(surface),
        onSurface = a(onSurface),
        surfaceVariant = a(surfaceVariant),
        onSurfaceVariant = a(onSurfaceVariant),
        outline = a(outline),
        outlineVariant = a(outlineVariant),
        surfaceContainer = a(surfaceContainer),
        surfaceContainerHigh = a(surfaceContainerHigh),
        surfaceContainerHighest = a(surfaceContainerHighest),
        error = a(error),
        errorContainer = a(errorContainer),
        onErrorContainer = a(onErrorContainer),
    )
}
