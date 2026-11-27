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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun PermissionsScreen(showOpenSettings: Boolean, onContinue: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                Icons.Filled.PhoneInTalk,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Text("Call nearby phones over Bluetooth", style = MaterialTheme.typography.headlineSmall)
            Text(
                "No internet, no SIM, no pairing. BlueCall needs a few permissions first:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Reason(Icons.Filled.Sensors, "Nearby devices", "To find, be found by and connect to phones with BlueCall open. Your location is never used.")
            Reason(Icons.Filled.Mic, "Microphone", "For your voice during calls. Only used while a call is in progress.")
            Reason(Icons.Filled.Notifications, "Notifications", "To ring for incoming calls and show call status on the lock screen.")
            Spacer(Modifier.weight(1f))
            if (showOpenSettings) {
                Text(
                    "Some permissions were denied. Turn them on in BlueCall's app settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
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
