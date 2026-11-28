package com.codemew.bluecall.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.data.NoiseSettings
import com.codemew.bluecall.ui.glass.GlassSurface
import com.codemew.bluecall.ui.glass.LocalMotionAllowed
import kotlinx.coroutines.delay

/** A round glass avatar with the person's initial. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    GlassSurface(modifier.size(size), shape = CircleShape, tint = tint) {
        Text(
            initial,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Soft rings expanding outwards, used while searching and while ringing.
 * Runs only while composed and on screen (Compose pauses frames when the screen is stopped).
 */
@Composable
fun PulseRings(color: Color, modifier: Modifier = Modifier, rings: Int = 3, periodMs: Int = 2400) {
    if (LocalMotionAllowed.current) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
            label = "pulseProgress",
        )
        Rings(color, modifier, rings) { progress }
    } else {
        Rings(color, modifier, rings) { 0.35f }
    }
}

@Composable
private fun Rings(color: Color, modifier: Modifier, rings: Int, progress: () -> Float) {
    Canvas(modifier) {
        val progress = progress()
        val maxRadius = size.minDimension / 2
        for (i in 0 until rings) {
            val p = (progress + i.toFloat() / rings) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.55f),
                radius = maxRadius * (0.35f + 0.65f * p),
                style = Stroke(width = (2.5f * (1f - p) + 0.5f).dp.toPx()),
            )
        }
    }
}

@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) on else off),
            )
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/**
 * Current wall-clock time, ticking once per second only while the screen
 * showing it is started. No ticking in the background.
 */
@Composable
fun rememberTickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L - now % 1_000L)
            }
        }
    }
    return now
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** The five noise-cancellation switches, shared by Settings and the in-call panel. */
@Composable
fun NoiseToggles(
    noise: NoiseSettings,
    availability: EffectAvailability,
    onChange: (NoiseSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ToggleRow(
            title = "Echo cancellation",
            subtitle = if (availability.echoCancellation) "Stops the other person hearing themselves" else "Not supported on this phone",
            checked = noise.echoCancellation,
            enabled = availability.echoCancellation,
            onChange = { onChange(noise.copy(echoCancellation = it)) },
        )
        ToggleRow(
            title = "Noise suppression",
            subtitle = if (availability.noiseSuppression) "The phone's built-in background-noise filter" else "Not supported on this phone",
            checked = noise.noiseSuppression,
            enabled = availability.noiseSuppression,
            onChange = { onChange(noise.copy(noiseSuppression = it)) },
        )
        ToggleRow(
            title = "Automatic gain",
            subtitle = if (availability.autoGain) "Keeps your voice at an even volume" else "Not supported on this phone",
            checked = noise.autoGain,
            enabled = availability.autoGain,
            onChange = { onChange(noise.copy(autoGain = it)) },
        )
        ToggleRow(
            title = "AI noise reduction",
            subtitle = "Removes fans, traffic and chatter (RNNoise). Uses a little more battery",
            checked = noise.aiNoiseReduction,
            enabled = true,
            onChange = { onChange(noise.copy(aiNoiseReduction = it)) },
        )
        ToggleRow(
            title = "Wind & rumble filter",
            subtitle = "Cuts low hum and wind noise below 100 Hz",
            checked = noise.lowCutFilter,
            enabled = true,
            onChange = { onChange(noise.copy(lowCutFilter = it)) },
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked && enabled,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            ),
        )
    }
}
