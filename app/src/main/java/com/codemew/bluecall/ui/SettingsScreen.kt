package com.codemew.bluecall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.NoiseSettings
import com.codemew.bluecall.ui.theme.CallGreen

class SettingsActions(
    val onBack: () -> Unit,
    val onName: (String) -> Unit,
    val onNoise: (NoiseSettings) -> Unit,
    val onUltraLowLatency: (Boolean) -> Unit,
    val onAutoOffline: (Int) -> Unit,
    val onAllowBattery: () -> Unit,
    val onSamsungBattery: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    deviceName: String,
    availability: EffectAvailability,
    batteryUnrestricted: Boolean,
    isSamsung: Boolean,
    versionText: String,
    actions: SettingsActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Section("Your name")
            NameField(settings.displayName, deviceName, actions.onName)

            Section("Noise cancellation")
            Text(
                "Each one can be turned off on its own. Changes apply instantly, even during a call.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoiseToggles(settings.noise, availability, actions.onNoise)

            Section("Call quality")
            ToggleRow(
                title = "Ultra-low latency",
                subtitle = "10 ms audio frames: about 10 ms less delay for slightly more battery. Applies from the next call.",
                checked = settings.ultraLowLatency,
                enabled = true,
                onChange = actions.onUltraLowLatency,
            )

            Section("Battery")
            Text("Go offline when idle", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            val choices = AppSettings.AUTO_OFFLINE_CHOICES
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, minutes ->
                    SegmentedButton(
                        selected = settings.autoOfflineMinutes == minutes,
                        onClick = { actions.onAutoOffline(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    ) {
                        Text(if (minutes == 0) "Never" else "$minutes min")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "If no call happens for this long while BlueCall is in the background, it shuts down completely. " +
                    "It never wakes your phone to do this; it checks the next time the phone wakes up anyway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (batteryUnrestricted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CallGreen)
                    Text(
                        "  Battery use: Unrestricted. Calls ring while locked.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                OutlinedButton(onClick = actions.onAllowBattery, modifier = Modifier.fillMaxWidth()) {
                    Text("Let BlueCall ring while the phone is locked")
                }
            }
            if (isSamsung) {
                TextButton(onClick = actions.onSamsungBattery) {
                    Text("Samsung: add BlueCall to Never sleeping apps")
                }
            }
            Text(
                "These settings only matter while BlueCall is open. After Exit nothing of BlueCall runs at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("About")
            Text(versionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NameField(saved: String, deviceName: String, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { text = saved }
    val focus = LocalFocusManager.current
    val changed = text.trim() != saved.trim()
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(AppSettings.MAX_DISPLAY_NAME) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Shown to nearby phones") },
        placeholder = { Text(deviceName) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onSave(text.trim())
            focus.clearFocus()
        }),
        trailingIcon = {
            if (changed) {
                TextButton(onClick = {
                    onSave(text.trim())
                    focus.clearFocus()
                }) { Text("Save") }
            }
        },
        supportingText = { Text("Leave empty to use “$deviceName”. Nearby phones see the first 13 characters.") },
    )
}
