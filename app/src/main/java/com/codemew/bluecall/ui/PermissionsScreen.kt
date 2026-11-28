package com.codemew.bluecall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.ui.glass.GlassButton
import com.codemew.bluecall.ui.glass.GlassCard
import com.codemew.bluecall.ui.glass.GlassSurface

@Composable
fun PermissionsScreen(showOpenSettings: Boolean, onContinue: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        GlassSurface(Modifier.size(84.dp), shape = CircleShape) {
            Icon(
                Icons.Filled.PhoneInTalk,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).align(Alignment.Center),
            )
        }
        Text("Call nearby phones over Bluetooth", style = MaterialTheme.typography.headlineSmall)
        Text(
            "No internet, no SIM, no pairing. BlueCall needs a few permissions first:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Reason(
                    Icons.Filled.Sensors,
                    "Nearby devices",
                    "To find, be found by and connect to phones with BlueCall open. Your location is never used.",
                )
                Reason(Icons.Filled.Mic, "Microphone", "For your voice during calls. Only used while a call is in progress.")
                Reason(
                    Icons.Filled.Notifications,
                    "Notifications",
                    "To ring for incoming calls and show call status on the lock screen.",
                )
            }
        }
        if (showOpenSettings) {
            Text(
                "Some permissions were denied. Turn them on in BlueCall's app settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            GlassButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
        }
        GlassButton(onClick = onContinue, modifier = Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primary) {
            Text("Continue", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
