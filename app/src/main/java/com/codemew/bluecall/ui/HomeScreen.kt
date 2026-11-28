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
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.call.CallEnd
import com.codemew.bluecall.call.EndReason
import com.codemew.bluecall.call.NearbyPeer
import com.codemew.bluecall.call.Transport
import com.codemew.bluecall.service.PresenceState
import com.codemew.bluecall.service.RadioStatus
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
    /** Android 12: Wi-Fi calls need location access, so the Wi-Fi notice explains that. */
    val wifiNeedsLocationPermission: Boolean,
    val notificationsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val batteryCardDismissed: Boolean,
    val isSamsung: Boolean,
)

class HomeActions(
    val onCall: (NearbyPeer, Transport) -> Unit,
    val onOpenSettings: () -> Unit,
    val onExit: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onEnableWifi: () -> Unit,
    val onAllowWifi: () -> Unit,
    val onLocationSettings: () -> Unit,
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
    val wifiNotice = wifiNotice(ui, actions)
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

            val reachable = ui.bluetoothOn || ui.presence.online
            if (ui.presence.online && !ui.bluetoothOn) {
                item(key = "bluetooth-off") {
                    NoticeCard(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = "Bluetooth is off",
                        body = "You can still call phones over Wi-Fi. Turn Bluetooth on to reach phones without Wi-Fi Aware too.",
                        action = "Turn on" to actions.onEnableBluetooth,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (ui.presence.online && wifiNotice != null) {
                item(key = "wifi") { wifiNotice(Modifier.animateItem()) }
            }
            if (reachable && !ui.notificationsAllowed) {
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
            if (reachable && !ui.fullScreenAllowed) {
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
            if (reachable && !ui.batteryUnrestricted && !ui.batteryCardDismissed) {
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
                    item(key = "searching") { SearchingRow(ui.presence, Modifier.animateItem()) }
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
        ui.presence.online -> StatusKind.ONLINE
        !ui.bluetoothOn -> StatusKind.BLUETOOTH_OFF
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
                            "BlueCall talks to nearby phones over Bluetooth, or direct Wi-Fi where both phones have it. " +
                                "No internet or pairing needed.",
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
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioChip(Icons.Filled.Bluetooth, Icons.Filled.BluetoothDisabled, "Bluetooth", ui.presence.bluetooth)
                            if (ui.presence.wifi != RadioStatus.UNSUPPORTED) {
                                RadioChip(Icons.Filled.Wifi, Icons.Filled.WifiOff, "Wi-Fi", ui.presence.wifi)
                            }
                        }
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
    onDismiss: (() -> Unit)? = null,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(onClick = action.second, tint = MaterialTheme.colorScheme.primary) { Text(action.first) }
                    if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Not now") }
                }
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
private fun SearchingRow(presence: PresenceState, modifier: Modifier = Modifier) {
    val bluetooth = presence.bluetooth == RadioStatus.ON
    val wifi = presence.wifi == RadioStatus.ON
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            PulseRings(MaterialTheme.colorScheme.primary, Modifier.matchParentSize())
            GlassSurface(Modifier.size(56.dp), shape = CircleShape) {
                Icon(
                    if (bluetooth || !wifi) Icons.Filled.Bluetooth else Icons.Filled.Wifi,
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
            when {
                bluetooth && wifi -> "Ask the other person to open BlueCall too. Bluetooth reaches about 10–30 m indoors; direct Wi-Fi usually a little further."
                wifi -> "Ask the other person to open BlueCall too, with Wi-Fi on. Direct Wi-Fi needs no router."
                else -> "Ask the other person to open BlueCall too. Bluetooth reaches about 10–30 m indoors."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PeerRow(peer: NearbyPeer, onCall: (NearbyPeer, Transport) -> Unit, modifier: Modifier = Modifier) {
    val only = peer.transports.singleOrNull()
    val rowModifier = if (only != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClickLabel = "Call over ${transportName(only)}",
            onClick = { onCall(peer, only) },
        )
    } else {
        Modifier
    }
    GlassSurface(modifier.fillMaxWidth().then(rowModifier), shape = RoundedCornerShape(24.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(peer.name, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    peer.bluetoothBars?.let { bars ->
                        Icon(
                            Icons.Filled.Bluetooth,
                            contentDescription = "Bluetooth",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(2.dp))
                        SignalBars(bars)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (peer.wifi) {
                        Icon(
                            Icons.Filled.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                peer.transports.forEach { transport ->
                    GlassIconButton(
                        onClick = { onCall(peer, transport) },
                        icon = if (transport == Transport.WIFI) Icons.Filled.Wifi else Icons.Filled.Bluetooth,
                        contentDescription = "Call ${peer.name} over ${transportName(transport)}",
                        size = 46.dp,
                        tint = CallGreen,
                    )
                }
            }
        }
    }
}

private fun transportName(transport: Transport) = if (transport == Transport.WIFI) "Wi-Fi" else "Bluetooth"

/** A small pill that says whether this phone is reachable over one radio. */
@Composable
private fun RadioChip(on: ImageVector, off: ImageVector, label: String, status: RadioStatus) {
    val active = status == RadioStatus.ON
    val text = when (status) {
        RadioStatus.ON -> label
        RadioStatus.OFF -> "$label off"
        RadioStatus.UNAVAILABLE -> "$label paused"
        RadioStatus.DISABLED -> "$label calls off"
        RadioStatus.NEEDS_LOCATION, RadioStatus.NO_PERMISSION -> "$label needs access"
        RadioStatus.UNSUPPORTED -> "No $label"
    }
    GlassSurface(
        shape = RoundedCornerShape(50),
        tint = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) on else off,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one Wi-Fi notice worth showing, if any. Dismissed ones stay hidden until BlueCall is reopened. */
@Composable
private fun wifiNotice(ui: HomeUi, actions: HomeActions): (@Composable (Modifier) -> Unit)? {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return null
    val notice: Triple<ImageVector, Pair<String, String>, Pair<String, () -> Unit>> = when (ui.presence.wifi) {
        RadioStatus.NO_PERMISSION -> Triple(
            Icons.Filled.Wifi,
            "Call over Wi-Fi too" to if (ui.wifiNeedsLocationPermission) {
                "Direct Wi-Fi calls need location access on Android 12. BlueCall never uses your location."
            } else {
                "Allow nearby Wi-Fi devices to find and call phones over direct Wi-Fi. No router needed."
            },
            "Allow" to actions.onAllowWifi,
        )

        RadioStatus.NEEDS_LOCATION -> Triple(
            Icons.Filled.LocationOff,
            "Turn on Location for Wi-Fi calls" to "On Android 12, direct Wi-Fi only works with Location on. BlueCall never uses your location.",
            "Open settings" to actions.onLocationSettings,
        )

        RadioStatus.OFF -> Triple(
            Icons.Filled.WifiOff,
            "Turn on Wi-Fi to call over Wi-Fi too" to "It doesn't need to be connected to a network: phones talk directly.",
            "Turn on" to actions.onEnableWifi,
        )

        else -> return null
    }
    return { modifier ->
        NoticeCard(
            icon = notice.first,
            title = notice.second.first,
            body = notice.second.second,
            action = notice.third,
            onDismiss = { dismissed = true },
            modifier = modifier,
        )
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
            "Stops calling over Bluetooth and Wi-Fi and closes the app completely. No battery use until you open it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
