package com.codemew.bluecall.ui.liquid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.codemew.bluecall.R
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Liquid colours flowing and mixing: an AGSL shader (res/raw/liquid.agsl) on
 * Android 13+, and drifting blurred colour blobs on Android 12.
 *
 * Battery: frames are produced only while [animate] is true and the screen
 * showing this is started. Otherwise a single still frame is drawn. While
 * animating it asks for the normal (60 Hz) frame-rate category on Android 15+,
 * so 120 Hz panels don't render the background twice as often as needed.
 *
 * @param speed time scale: 1 for the lively call screen, ~0.25 for a slow drift.
 * @param resolutionScale render the shader at this fraction of the size and scale it up;
 *   the soft colour fields look the same at half resolution for a quarter of the GPU work.
 */
@Composable
fun LiquidBackground(
    colors: List<Color>,
    speed: Float,
    animate: Boolean,
    modifier: Modifier = Modifier,
    resolutionScale: Float = 1f,
) {
    val palette = remember(colors) { List(4) { i -> colors.getOrElse(i) { colors.lastOrNull() ?: Color.DarkGray } } }
    val time = rememberLiquidTime(speed, animate)
    val frameRate = if (animate) Modifier.preferredFrameRate(FrameRateCategory.Normal) else Modifier
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = rememberLiquidShader()
        if (shader != null) {
            ShaderLiquid(shader, palette, time, modifier, frameRate, resolutionScale.coerceIn(0.25f, 1f))
            return
        }
    }
    BlobLiquid(palette, time, modifier.then(frameRate))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderLiquid(
    shader: RuntimeShader,
    colors: List<Color>,
    time: FloatState,
    modifier: Modifier,
    frameRate: Modifier,
    scale: Float,
) {
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize(scale)
                .then(frameRate)
                .graphicsLayer {
                    if (scale < 1f) {
                        // Render into a smaller offscreen buffer, then stretch it to full size.
                        scaleX = 1f / scale
                        scaleY = 1f / scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
                .drawBehind {
                    // Reading time here only invalidates drawing; nothing recomposes per frame.
                    shader.setFloatUniform("iResolution", size.width, size.height)
                    shader.setFloatUniform("iTime", time.floatValue)
                    shader.setColorUniform("c0", colors[0].toArgb())
                    shader.setColorUniform("c1", colors[1].toArgb())
                    shader.setColorUniform("c2", colors[2].toArgb())
                    shader.setColorUniform("c3", colors[3].toArgb())
                    drawRect(brush)
                },
        )
    }
}

/** Android 12 fallback, and the fallback if the shader can't be compiled. */
@Composable
private fun BlobLiquid(colors: List<Color>, time: FloatState, modifier: Modifier) {
    Canvas(modifier.blur(72.dp, BlurredEdgeTreatment.Rectangle)) {
        val t = time.floatValue
        drawRect(colors[0])
        val r = max(size.width, size.height) * 0.55f
        val paths = listOf(
            Offset(0.25f + 0.20f * sin(t * 0.31f), 0.20f + 0.15f * cos(t * 0.23f)),
            Offset(0.75f + 0.18f * cos(t * 0.27f), 0.45f + 0.20f * sin(t * 0.19f)),
            Offset(0.35f + 0.22f * sin(t * 0.17f + 2f), 0.80f + 0.12f * cos(t * 0.29f)),
            Offset(0.65f + 0.20f * cos(t * 0.21f + 1f), 0.10f + 0.18f * sin(t * 0.33f + 1f)),
        )
        paths.forEachIndexed { i, p ->
            val color = colors[(i + 1) % 4]
            val center = Offset(p.x * size.width, p.y * size.height)
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, r), r, center)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberLiquidShader(): RuntimeShader? {
    val resources = LocalResources.current
    return remember(resources) {
        try {
            val source = resources.openRawResource(R.raw.liquid).bufferedReader().use { it.readText() }
            RuntimeShader(source)
        } catch (e: RuntimeException) {
            Log.e("LiquidBackground", "liquid shader unavailable, using fallback", e)
            null
        }
    }
}

/**
 * Seconds of liquid motion, advanced once per frame only while [animate] is true
 * and the hosting screen is started. Starts mid-flow so the still frame looks good.
 */
@Composable
fun rememberLiquidTime(speed: Float, animate: Boolean): FloatState {
    val time = remember { mutableFloatStateOf(START_TIME) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animate, speed, lifecycle) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = -1L
            while (isActive) {
                withFrameNanos { now ->
                    if (last >= 0) {
                        val dt = (now - last).coerceAtMost(MAX_FRAME_STEP_NS) / 1_000_000_000f
                        time.floatValue += dt * speed
                    }
                    last = now
                }
            }
        }
    }
    return time
}

/**
 * Whether background motion may run: the user's setting is on, Battery Saver
 * is off, and system animations haven't been turned off ("Remove animations").
 */
@Composable
fun rememberMotionAllowed(userEnabled: Boolean): Boolean {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var powerSave by remember { mutableStateOf(powerManager.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                powerSave = powerManager.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val systemAnimations = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    return userEnabled && !powerSave && systemAnimations
}

private const val START_TIME = 37f
private const val MAX_FRAME_STEP_NS = 100_000_000L
