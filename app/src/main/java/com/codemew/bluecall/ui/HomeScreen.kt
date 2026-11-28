package com.codemew.bluecall.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneLocked
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.bluetooth.NearbyPeer
import com.codemew.bluecall.call.CallEnd
import com.codemew.bluecall.call.EndReason
import com.codemew.bluecall.service.PresenceState
import com.codemew.bluecall.ui.glass.GlassButton
import com.codemew.bluecall.ui.glass.GlassCard
import com.codemew.bluecall.ui.glass.GlassIconButton
import com.codemew.bluecall.ui.glass.GlassSurface
import com.codemew.bluecall.ui.theme.CallGreen
import com.codemew.bluecall.ui.theme.HangUpRed

data class HomeUi(
    val presence: PresenceState,
    val peers: List<NearbyPeer>,
    val lastEnd: CallEnd?,
    val bluetoothOn: Boolean,
    val notificationsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val batteryCardDismissed: Boolean,
    val isSamsung: Boolean,
)

class HomeActions(
    val onCall: (NearbyPeer) -> Unit,
    val onOpenSettings: () -> Unit,
    val onExit: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onAllowNotifications: () -> Unit,
    val onAllowFullScreen: () -> Unit,
    val onAllowBattery: () -> Unit,
    val onDismissBattery: () -> Unit,
    val onSamsungBattery: () -> Unit,
)

private val listSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ui: HomeUi, actions: HomeActions) {
    // Only ticks while a "call ended" banner may be showing.
    val recentEnd = ui.lastEnd?.let { end -> end.takeIf { rememberTickingNow() - it.atMs < 8_000 } }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("BlueCall", fontWeight = FontWeight.SemiBold) },
                actions = {
                    GlassIconButton(
                        onClick = actions.onOpenSettings,
                        icon = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = { ExitBar(actions.onExit) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") { StatusCard(ui, actions, Modifier.animateItem()) }

            if (ui.bluetoothOn && !ui.notificationsAllowed) {
                item(key = "notifications") {
                    NoticeCard(
                        icon = Icons.Filled.NotificationsOff,
                        title = "Notifications are off",
                        body = "Without them you won't hear incoming calls while the phone is locked.",
                        action = "Allow" to actions.onAllowNotifications,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (ui.bluetoothOn && !ui.fullScreenAllowed) {
                item(key = "fullscreen") {
                    NoticeCard(
                        icon = Icons.Filled.PhoneLocked,
                        title = "Show calls on the lock screen",
                        body = "Allow full-screen notifications so incoming calls fill the screen when the phone is locked.",
                        action = "Allow" to actions.onAllowFullScreen,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (ui.bluetoothOn && !ui.batteryUnrestricted && !ui.batteryCardDismissed) {
                item(key = "battery") { BatteryCard(ui.isSamsung, actions, Modifier.animateItem()) }
            }

            recentEnd?.let { end ->
                item(key = "ended") { LastCallBanner(end, Modifier.animateItem()) }
            }

            if (ui.presence.online) {
                item(key = "header") {
                    SectionTitle("Nearby phones", Modifier.animateItem().padding(top = 8.dp))
                }
                if (ui.peers.isEmpty()) {
                    item(key = "searching") { SearchingRow(Modifier.animateItem()) }
                } else {
                    items(ui.peers, key = { it.sessionId }) { peer ->
                        PeerRow(
                            peer,
                            actions.onCall,
                            Modifier.animateItem(fadeInSpec = listSpring, fadeOutSpec = listSpring),
                        )
                    }
                }
            }
        }
    }
}

private enum class StatusKind { BLUETOOTH_OFF, ONLINE, STARTING, OFFLINE }

@Composable
private fun StatusCard(ui: HomeUi, actions: HomeActions, modifier: Modifier = Modifier) {
    val kind = when {
        !ui.bluetoothOn -> StatusKind.BLUETOOTH_OFF
        ui.presence.online -> StatusKind.ONLINE
        ui.presence.error == null -> StatusKind.STARTING
        else -> StatusKind.OFFLINE
    }
    GlassCard(modifier) {
        AnimatedContent(
            targetState = kind,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(initialScale = 0.96f))
                    .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
            },
            label = "status",
        ) { state ->
            Column {
                when (state) {
                    StatusKind.BLUETOOTH_OFF -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "BlueCall talks to nearby phones over Bluetooth. No internet or pairing needed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        GlassButton(onClick = actions.onEnableBluetooth, tint = MaterialTheme.colorScheme.primary) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Turn on Bluetooth")
                        }
                    }

                    StatusKind.ONLINE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                PulseRings(CallGreen, Modifier.matchParentSize(), rings = 2, periodMs = 2800)
                                StatusDot(CallGreen)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Online", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Nearby phones with BlueCall open can see you as “${ui.presence.name}”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ui.presence.error?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    StatusKind.STARTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Starting…", style = MaterialTheme.typography.titleMedium)
                    }

                    StatusKind.OFFLINE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Text("Offline", style = MaterialTheme.typography.titleMedium)
                        }
                        ui.presence.error?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: Pair<String, () -> Unit>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                GlassButton(onClick = action.second, tint = MaterialTheme.colorScheme.primary) { Text(action.first) }
            }
        }
    }
}

@Composable
private fun BatteryCard(isSamsung: Boolean, actions: HomeActions, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Stay reachable while locked", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isSamsung) {
                        "Samsung can pause apps in the background. Set BlueCall to “Unrestricted”, and add it to " +
                            "Never sleeping apps. This only matters while BlueCall is open; after Exit it uses no battery."
                    } else {
                        "Set BlueCall's battery use to “Unrestricted” so calls still ring while the phone is locked. " +
                            "This only matters while BlueCall is open; after Exit it uses no battery."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(onClick = actions.onAllowBattery, tint = MaterialTheme.colorScheme.primary) { Text("Allow") }
                    if (isSamsung) {
                        TextButton(onClick = actions.onSamsungBattery) { Text("Never sleeping apps") }
                    }
                    TextButton(onClick = actions.onDismissBattery) { Text("Not now") }
                }
            }
        }
    }
}

@Composable
private fun LastCallBanner(end: CallEnd, modifier: Modifier = Modifier) {
    val text = when (end.reason) {
        EndReason.HUNG_UP, EndReason.REMOTE_HUNG_UP -> "Call with ${end.peer.name} ended"
        EndReason.DECLINED -> "You declined ${end.peer.name}"
        EndReason.REMOTE_DECLINED -> "${end.peer.name} declined the call"
        EndReason.BUSY -> "${end.peer.name} is on another call"
        EndReason.NO_ANSWER -> "${end.peer.name} didn't answer"
        EndReason.MISSED -> "Missed call from ${end.peer.name}"
        EndReason.CONNECTION_FAILED -> "Couldn't reach ${end.peer.name}. Move closer and try again"
        EndReason.LINK_LOST -> "Connection to ${end.peer.name} was lost"
        EndReason.CANCELLED -> "Call cancelled"
    }
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tint = MaterialTheme.colorScheme.tertiary) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun SearchingRow(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            PulseRings(MaterialTheme.colorScheme.primary, Modifier.matchParentSize())
            GlassSurface(Modifier.size(56.dp), shape = CircleShape) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Looking for phones with BlueCall open…", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Ask the other person to open BlueCall too. Bluetooth reaches about 10–30 m indoors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PeerRow(peer: NearbyPeer, onCall: (NearbyPeer) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(
        modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onCall(peer) },
            ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(peer.name, 46.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalBars(peer.signalBars)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Tap to call",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GlassIconButton(
                onClick = { onCall(peer) },
                icon = Icons.Filled.Call,
                contentDescription = "Call ${peer.name}",
                size = 48.dp,
                tint = CallGreen,
            )
        }
    }
}

@Composable
private fun ExitBar(onExit: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassButton(onClick = onExit, modifier = Modifier.fillMaxWidth(), tint = HangUpRed) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Exit BlueCall", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Stops Bluetooth calling and closes the app completely. No battery use until you open it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
