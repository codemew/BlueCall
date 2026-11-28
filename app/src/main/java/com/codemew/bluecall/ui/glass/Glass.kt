package com.codemew.bluecall.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.data.GlassStyle
import com.codemew.bluecall.ui.liquid.LiquidBackground
import com.codemew.bluecall.ui.liquid.rememberMotionAllowed
import com.codemew.bluecall.ui.theme.BlueCallLook
import com.codemew.bluecall.ui.theme.LocalBlueCallLook
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** The backdrop that glass elements blur. Provided by [GlassBackdrop]. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** False in Battery Saver, with system animations off, or with "Animated backgrounds" off. */
val LocalMotionAllowed = staticCompositionLocalOf { true }

/** Spring used for presses and selections: quick, with a soft settle. */
private val pressSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

/**
 * Liquid colours behind everything, with a light scrim for readability. Glass
 * elements inside [content] blur and tint this backdrop.
 *
 * @param speed liquid flow speed (1 = lively call screen, ~0.25 = slow drift).
 * @param resolutionScale fraction of full resolution the liquid is rendered at.
 */
@Composable
fun GlassBackdrop(
    speed: Float,
    modifier: Modifier = Modifier,
    resolutionScale: Float = 0.5f,
    scrim: Float = -1f,
    liquid: List<Color> = LocalBlueCallLook.current.liquid,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = LocalBlueCallLook.current
    val hazeState = rememberHazeState()
    val animate = rememberMotionAllowed(look.motion)
    val scrimAlpha = if (scrim >= 0f) scrim else if (look.dark) 0.32f else 0.38f
    val scrimColor = if (look.dark) Color.Black else Color.White
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .hazeSource(hazeState),
        ) {
            LiquidBackground(liquid, speed, animate, Modifier.matchParentSize(), resolutionScale)
            Box(
                Modifier
                    .matchParentSize()
                    .background(scrimColor.copy(alpha = scrimAlpha)),
            )
        }
        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalMotionAllowed provides animate,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) { content() }
    }
}

/**
 * Frosted glass: blurs the liquid backdrop behind it, tints it, and adds a
 * light-catching rim and a soft top highlight. [tint] colours the glass
 * (green answer, red hang-up); unspecified means neutral glass.
 */
fun Modifier.glass(
    look: BlueCallLook,
    hazeState: HazeState?,
    shape: Shape,
    tint: Color = Color.Unspecified,
    pressed: Float = 0f,
): Modifier {
    val neutral = if (look.dark) Color(0xFF151A22) else Color.White
    val base = if (tint.isSpecified) tint else neutral
    val (blur, alpha, noise) = when (look.glass) {
        GlassStyle.CLEAR -> Triple(14.dp, if (tint.isSpecified) 0.55f else 0.12f, 0f)
        GlassStyle.FROSTED -> Triple(26.dp, if (tint.isSpecified) 0.70f else 0.30f, 0.06f)
        GlassStyle.SOLID -> Triple(0.dp, if (tint.isSpecified) 0.95f else 0.88f, 0f)
    }
    var m = this.clip(shape)
    m = if (hazeState != null && look.glass != GlassStyle.SOLID) {
        val style = HazeBlurStyle {
            blurRadius(blur)
            noiseFactor(noise)
            colorEffects(listOf(HazeColorEffect.tint(base.copy(alpha = alpha))))
            fallbackColorEffect(HazeColorEffect.tint(base.copy(alpha = (alpha + 0.45f).coerceAtMost(0.92f))))
        }
        m.hazeBlur(HazeInput.Sources(hazeState), style, HazePerformanceMode.Balanced)
    } else {
        m.background(base.copy(alpha = alpha))
    }
    val rimTop = Color.White.copy(alpha = if (look.dark) 0.30f else 0.75f)
    val rimBottom = Color.White.copy(alpha = if (look.dark) 0.05f else 0.20f)
    return m
        .border(1.dp, Brush.linearGradient(listOf(rimTop, rimBottom)), shape)
        .drawWithContent {
            drawContent()
            // Soft specular highlight across the top, brighter while pressed.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.14f + 0.10f * pressed),
                    0.5f to Color.Transparent,
                ),
            )
            if (pressed > 0f) drawRect(Color.White.copy(alpha = 0.10f * pressed))
        }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(LocalBlueCallLook.current, LocalHazeState.current, shape, tint), content = content)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier.fillMaxWidth(), tint = tint) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

/** A glass pill button that squishes on press with a spring. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentColor: Color = if (tint.isSpecified) Color.White else MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, pressSpring, label = "press")
    Box(
        modifier
            .graphicsLayer {
                val s = 1f - 0.05f * press
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.45f
            }
            .glass(LocalBlueCallLook.current, LocalHazeState.current, shape, tint, press)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** A round glass button with an icon. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Color.Unspecified,
    iconTint: Color = if (tint.isSpecified) Color.White else MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, pressSpring, label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 1f - 0.08f * press
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.45f
            }
            .glass(LocalBlueCallLook.current, LocalHazeState.current, CircleShape, tint, press)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(size * 0.45f))
    }
}

/** A round glass toggle with a label: lights up (solid white glass) when on. */
@Composable
fun GlassToggleButton(
    checked: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    enabled: Boolean = true,
) {
    val onColor = MaterialTheme.colorScheme.onSurface
    val tint by animateColorAsState(if (checked) onColor else Color.Transparent, spring(), label = "toggleTint")
    val iconColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.surface else onColor,
        spring(),
        label = "toggleIcon",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassIconButton(
            onClick = onClick,
            icon = icon,
            contentDescription = label,
            size = size,
            tint = if (checked) tint else Color.Unspecified,
            iconTint = iconColor,
            enabled = enabled,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A glass track with a sliding glass thumb behind the selected option. */
@Composable
fun GlassSegmentedRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalBlueCallLook.current
    val haze = LocalHazeState.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .glass(look, haze, RoundedCornerShape(50)),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(
            segment * selected.coerceIn(0, options.lastIndex),
            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
            label = "segment",
        )
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (look.dark) 0.55f else 0.85f)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val isSelected = i == selected
                val color by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    label = "segmentText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(i) },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (isSelected && look.dark) Color.White else color,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
