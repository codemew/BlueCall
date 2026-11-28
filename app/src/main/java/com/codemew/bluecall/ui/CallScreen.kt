package com.codemew.bluecall.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.audio.RouteType
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.call.peer
import com.codemew.bluecall.data.NoiseSettings
import com.codemew.bluecall.service.CallUiState
import com.codemew.bluecall.service.LinkQuality
import com.codemew.bluecall.ui.glass.GlassBackdrop
import com.codemew.bluecall.ui.glass.GlassButton
import com.codemew.bluecall.ui.glass.GlassIconButton
import com.codemew.bluecall.ui.glass.GlassSurface
import com.codemew.bluecall.ui.glass.GlassToggleButton
import com.codemew.bluecall.ui.glass.LocalMotionAllowed
import com.codemew.bluecall.ui.theme.CallGreen
import com.codemew.bluecall.ui.theme.HangUpRed

class CallActions(
    val onAnswer: () -> Unit,
    val onDecline: () -> Unit,
    val onHangUp: () -> Unit,
    val onMute: () -> Unit,
    val onSpeaker: () -> Unit,
    val onRoute: (RouteType) -> Unit,
    val onNoise: (NoiseSettings) -> Unit,
)

private enum class Panel { NONE, NOISE, ROUTES }

private enum class Controls { INCOMING, IN_CALL, ENDED }

private val gentle = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * The call screen: liquid colours flowing at full speed and full resolution
 * behind glass controls. Ringing shows pulsing halos around the avatar.
 */
@Composable
fun CallScreen(
    state: CallState,
    ui: CallUiState,
    noise: NoiseSettings,
    availability: EffectAvailability,
    actions: CallActions,
) {
    val peer = state.peer
    var panel by remember { mutableStateOf(Panel.NONE) }
    val ringing = state is CallState.Incoming || state is CallState.Ringing || state is CallState.Dialing

    GlassBackdrop(speed = 1f, resolutionScale = 1f, scrim = 0.24f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            GlassSurface(shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("BlueCall", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(36.dp))
            AvatarWithHalo(peer?.name ?: "", ringing)
            Spacer(Modifier.height(18.dp))
            Text(
                peer?.name ?: "Call ended",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            StatusLine(state, ui)
            Spacer(Modifier.weight(1f))

            val controls = when (state) {
                is CallState.Incoming -> Controls.INCOMING
                is CallState.Dialing, is CallState.Ringing, is CallState.Active -> Controls.IN_CALL
                else -> Controls.ENDED
            }
            AnimatedContent(
                targetState = controls,
                transitionSpec = {
                    (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(gentle))
                        .togetherWith(slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it / 3 } + fadeOut())
                },
                label = "controls",
            ) { kind ->
                when (kind) {
                    Controls.INCOMING -> IncomingControls(actions)
                    Controls.IN_CALL -> InCallControls(
                        active = state is CallState.Active,
                        ui = ui,
                        actions = actions,
                        onNoise = { panel = Panel.NOISE },
                        onRoutes = { panel = Panel.ROUTES },
                    )

                    Controls.ENDED -> Spacer(Modifier.height(140.dp).fillMaxWidth())
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        GlassPanel(visible = panel == Panel.NOISE, onDismiss = { panel = Panel.NONE }) {
            Text("Noise cancellation", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            NoiseToggles(noise, availability, actions.onNoise)
        }
        GlassPanel(visible = panel == Panel.ROUTES, onDismiss = { panel = Panel.NONE }) {
            Text("Audio output", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ui.routes.forEach { route ->
                    val selected = route.type == ui.route
                    GlassButton(
                        onClick = {
                            actions.onRoute(route.type)
                            panel = Panel.NONE
                        },
                        modifier = Modifier.fillMaxWidth(),
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    ) {
                        Icon(routeIcon(route.type), contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(route.name, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarWithHalo(name: String, ringing: Boolean) {
    // Breathe only while ringing (and motion is allowed), so nothing ticks during a call.
    val breath = if (ringing && LocalMotionAllowed.current) breathing(1.05f, 1400) else 1f
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(ringing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
            PulseRings(Color.White, Modifier.fillMaxSize(), rings = 3, periodMs = 2600)
        }
        Avatar(
            name,
            124.dp,
            Modifier.graphicsLayer {
                scaleX = breath
                scaleY = breath
            },
        )
    }
}

@Composable
private fun StatusLine(state: CallState, ui: CallUiState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(gentle).togetherWith(fadeOut(gentle)) },
        contentKey = { it::class },
        label = "status",
    ) { shown ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (shown) {
                is CallState.Dialing -> Text("Connecting…", color = muted)
                is CallState.Ringing -> Text("Ringing…", color = muted)
                is CallState.Incoming -> Text("Incoming call", color = muted)
                is CallState.Active -> {
                    val now = rememberTickingNow()
                    Text(formatDuration(now - shown.connectedAtMs), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    val details = buildList {
                        ui.latencyMs?.let { add("≈ $it ms delay") }
                        when (ui.linkQuality) {
                            LinkQuality.EXCELLENT -> add("Excellent link")
                            LinkQuality.GOOD -> add("Good link")
                            LinkQuality.POOR -> add("Weak link: move closer")
                            LinkQuality.UNKNOWN -> Unit
                        }
                    }
                    if (details.isNotEmpty()) {
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    val notes = buildList {
                        if (ui.onHold) add("On hold. Another call is using audio")
                        if (ui.peerOnHold) add("The other phone put you on hold")
                        if (ui.peerMuted) add("The other person is muted")
                        ui.error?.let { add(it) }
                    }
                    notes.forEach {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }

                else -> Text("Call ended", color = muted)
            }
        }
    }
}

@Composable
private fun breathing(to: Float, periodMs: Int): Float {
    val transition = rememberInfiniteTransition(label = "breath")
    val value by transition.animateFloat(
        initialValue = 1f,
        targetValue = to,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathValue",
    )
    return value
}

@Composable
private fun IncomingControls(actions: CallActions) {
    val glow = if (LocalMotionAllowed.current) breathing(1.08f, 900) else 1f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        RoundAction(Icons.Filled.CallEnd, "Decline", HangUpRed, actions.onDecline)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                PulseRings(CallGreen, Modifier.matchParentSize(), rings = 2, periodMs = 1800)
                GlassIconButton(
                    onClick = actions.onAnswer,
                    icon = Icons.Filled.Call,
                    contentDescription = "Answer",
                    size = 78.dp,
                    tint = CallGreen,
                    modifier = Modifier.graphicsLayer {
                        scaleX = glow
                        scaleY = glow
                    },
                )
            }
            Text("Answer", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun InCallControls(
    active: Boolean,
    ui: CallUiState,
    actions: CallActions,
    onNoise: () -> Unit,
    onRoutes: () -> Unit,
) {
    val hasExternal = ui.routes.any { it.type == RouteType.BLUETOOTH || it.type == RouteType.WIRED }
    val routeLabel = when (ui.route) {
        RouteType.SPEAKER -> "Speaker"
        RouteType.BLUETOOTH -> ui.routes.firstOrNull { it.type == RouteType.BLUETOOTH }?.name ?: "Bluetooth"
        RouteType.WIRED -> "Headset"
        else -> if (hasExternal) "Phone" else "Speaker"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            GlassToggleButton(
                checked = ui.muted,
                onClick = actions.onMute,
                icon = if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (ui.muted) "Unmute" else "Mute",
                enabled = active,
            )
            GlassToggleButton(
                checked = ui.route == RouteType.SPEAKER,
                onClick = { if (hasExternal) onRoutes() else actions.onSpeaker() },
                icon = ui.route?.let(::routeIcon) ?: Icons.AutoMirrored.Filled.VolumeUp,
                label = routeLabel,
            )
            GlassToggleButton(checked = false, onClick = onNoise, icon = Icons.Filled.GraphicEq, label = "Noise")
        }
        Spacer(Modifier.height(30.dp))
        RoundAction(Icons.Filled.CallEnd, "Hang up", HangUpRed, actions.onHangUp)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            GlassIconButton(onClick = onClick, icon = icon, contentDescription = label, size = 78.dp, tint = color)
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** An in-window glass sheet (so it can blur the liquid behind it), with a tap-outside scrim. */
@Composable
private fun BoxScope.GlassPanel(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        GlassSurface(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(32.dp),
        ) {
            Column(
                Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

private fun routeIcon(type: RouteType): ImageVector = when (type) {
    RouteType.EARPIECE -> Icons.Filled.PhoneAndroid
    RouteType.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    RouteType.BLUETOOTH -> Icons.Filled.Bluetooth
    RouteType.WIRED -> Icons.Filled.Headset
}
