package com.codemew.bluecall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.audio.RouteType
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.call.peer
import com.codemew.bluecall.data.NoiseSettings
import com.codemew.bluecall.service.CallUiState
import com.codemew.bluecall.service.LinkQuality
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    state: CallState,
    ui: CallUiState,
    noise: NoiseSettings,
    availability: EffectAvailability,
    actions: CallActions,
) {
    val peer = state.peer
    var showNoise by remember { mutableStateOf(false) }
    val background = Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                "BlueCall · Bluetooth",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(32.dp))
            Avatar(peer?.name ?: "", 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                peer?.name ?: "Call ended",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            StatusLine(state, ui)
            Spacer(Modifier.weight(1f))

            when (state) {
                is CallState.Incoming -> IncomingControls(actions)
                is CallState.Dialing, is CallState.Ringing, is CallState.Active -> InCallControls(
                    active = state is CallState.Active,
                    ui = ui,
                    actions = actions,
                    onNoise = { showNoise = true },
                )

                else -> Spacer(Modifier.height(120.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showNoise) {
        ModalBottomSheet(onDismissRequest = { showNoise = false }) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Text("Noise cancellation", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                NoiseToggles(noise, availability, actions.onNoise)
            }
        }
    }
}

@Composable
private fun StatusLine(state: CallState, ui: CallUiState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (state) {
        is CallState.Dialing -> Text("Connecting…", color = muted)
        is CallState.Ringing -> Text("Ringing…", color = muted)
        is CallState.Incoming -> Text("Incoming call", color = muted)
        is CallState.Active -> {
            val now = rememberTickingNow()
            Text(
                formatDuration(now - state.connectedAtMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
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

@Composable
private fun IncomingControls(actions: CallActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Filled.CallEnd, "Decline", HangUpRed, actions.onDecline)
        RoundAction(Icons.Filled.Call, "Answer", CallGreen, actions.onAnswer)
    }
}

@Composable
private fun InCallControls(active: Boolean, ui: CallUiState, actions: CallActions, onNoise: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToggleAction(
                icon = if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (ui.muted) "Unmute" else "Mute",
                checked = ui.muted,
                enabled = active,
                onClick = actions.onMute,
            )
            RouteAction(ui, actions)
            ToggleAction(
                icon = Icons.Filled.GraphicEq,
                label = "Noise",
                checked = false,
                enabled = true,
                onClick = onNoise,
            )
        }
        Spacer(Modifier.height(32.dp))
        RoundAction(Icons.Filled.CallEnd, "Hang up", HangUpRed, actions.onHangUp)
    }
}

@Composable
private fun RouteAction(ui: CallUiState, actions: CallActions) {
    val hasExternal = ui.routes.any { it.type == RouteType.BLUETOOTH || it.type == RouteType.WIRED }
    var menu by remember { mutableStateOf(false) }
    val icon = when (ui.route) {
        RouteType.BLUETOOTH -> Icons.Filled.Bluetooth
        RouteType.WIRED -> Icons.Filled.Headset
        RouteType.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
        else -> if (hasExternal) Icons.Filled.PhoneAndroid else Icons.AutoMirrored.Filled.VolumeUp
    }
    val label = when (ui.route) {
        RouteType.SPEAKER -> "Speaker"
        RouteType.BLUETOOTH -> ui.routes.firstOrNull { it.type == RouteType.BLUETOOTH }?.name ?: "Bluetooth"
        RouteType.WIRED -> "Headset"
        else -> if (hasExternal) "Phone" else "Speaker"
    }
    Box {
        ToggleAction(
            icon = icon,
            label = label,
            checked = ui.route == RouteType.SPEAKER,
            enabled = true,
            onClick = { if (hasExternal) menu = true else actions.onSpeaker() },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            ui.routes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(route.name) },
                    leadingIcon = {
                        Icon(
                            when (route.type) {
                                RouteType.EARPIECE -> Icons.Filled.PhoneAndroid
                                RouteType.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
                                RouteType.BLUETOOTH -> Icons.Filled.Bluetooth
                                RouteType.WIRED -> Icons.Filled.Headset
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menu = false
                        actions.onRoute(route.type)
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, size: Dp = 72.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
