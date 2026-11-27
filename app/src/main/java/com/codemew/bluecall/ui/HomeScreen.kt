package com.codemew.bluecall.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneLocked
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.bluetooth.NearbyPeer
import com.codemew.bluecall.call.CallEnd
import com.codemew.bluecall.call.EndReason
import com.codemew.bluecall.service.PresenceState
import com.codemew.bluecall.ui.theme.CallGreen

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ui: HomeUi, actions: HomeActions) {
    // Only ticks while a "call ended" banner may be showing.
    val recentEnd = ui.lastEnd?.let { end -> end.takeIf { rememberTickingNow() - it.atMs < 8_000 } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BlueCall", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
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
            item { StatusCard(ui, actions) }

            if (ui.bluetoothOn && !ui.notificationsAllowed) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.NotificationsOff,
                        title = "Notifications are off",
                        body = "Without them you won't hear incoming calls while the phone is locked.",
                        action = "Allow" to actions.onAllowNotifications,
                    )
                }
            }
            if (ui.bluetoothOn && !ui.fullScreenAllowed) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.PhoneLocked,
                        title = "Show calls on the lock screen",
                        body = "Allow full-screen notifications so incoming calls fill the screen when the phone is locked.",
                        action = "Allow" to actions.onAllowFullScreen,
                    )
                }
            }
            if (ui.bluetoothOn && !ui.batteryUnrestricted && !ui.batteryCardDismissed) {
                item {
                    BatteryCard(ui.isSamsung, actions)
                }
            }

            recentEnd?.let { end ->
                item { LastCallBanner(end) }
            }

            if (ui.presence.online) {
                item {
                    Text(
                        "Nearby phones",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                if (ui.peers.isEmpty()) {
                    item { SearchingRow() }
                } else {
                    items(ui.peers, key = { it.sessionId }) { peer -> PeerRow(peer, actions.onCall) }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(ui: HomeUi, actions: HomeActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            when {
                !ui.bluetoothOn -> {
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
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions.onEnableBluetooth) { Text("Turn on Bluetooth") }
                }

                ui.presence.online -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(CallGreen)
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

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ui.presence.error == null) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Starting…", style = MaterialTheme.typography.titleMedium)
                        } else {
                            StatusDot(MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Text("Offline", style = MaterialTheme.typography.titleMedium)
                        }
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

@Composable
private fun NoticeCard(icon: ImageVector, title: String, body: String, action: Pair<String, () -> Unit>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = action.second, contentPadding = PaddingValues(0.dp)) { Text(action.first) }
            }
        }
    }
}

@Composable
private fun BatteryCard(isSamsung: Boolean, actions: HomeActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val color = MaterialTheme.colorScheme.onSecondaryContainer
                Text("Stay reachable while locked", style = MaterialTheme.typography.titleSmall, color = color)
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
                    color = color,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = actions.onAllowBattery, contentPadding = PaddingValues(0.dp)) { Text("Allow") }
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
private fun LastCallBanner(end: CallEnd) {
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
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SearchingRow() {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text("Looking for phones with BlueCall open…", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Ask the other person to open BlueCall too. Bluetooth reaches about 10–30 m indoors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeerRow(peer: NearbyPeer, onCall: (NearbyPeer) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onCall(peer) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(peer.name, 44.dp)
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
            FilledIconButton(
                onClick = { onCall(peer) },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallGreen, contentColor = Color.White),
            ) {
                Icon(Icons.Filled.Call, contentDescription = "Call ${peer.name}")
            }
        }
    }
}

@Composable
private fun ExitBar(onExit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Exit BlueCall", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Stops Bluetooth calling and closes the app completely. No battery use until you open it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
