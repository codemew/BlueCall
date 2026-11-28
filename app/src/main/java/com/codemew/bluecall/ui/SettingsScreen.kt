package com.codemew.bluecall.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.GlassStyle
import com.codemew.bluecall.data.NoiseSettings
import com.codemew.bluecall.data.ThemeMode
import com.codemew.bluecall.ui.glass.GlassButton
import com.codemew.bluecall.ui.glass.GlassCard
import com.codemew.bluecall.ui.glass.GlassIconButton
import com.codemew.bluecall.ui.glass.GlassSegmentedRow
import com.codemew.bluecall.ui.theme.CallGreen
import com.codemew.bluecall.ui.theme.LocalBlueCallLook
import com.codemew.bluecall.ui.theme.Palettes
import kotlinx.coroutines.delay

class SettingsActions(
    val onBack: () -> Unit,
    val onName: (String) -> Unit,
    val onNoise: (NoiseSettings) -> Unit,
    val onUltraLowLatency: (Boolean) -> Unit,
    val onAutoOffline: (Int) -> Unit,
    val onAllowBattery: () -> Unit,
    val onSamsungBattery: () -> Unit,
    val onPalette: (String) -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onCustomHue: (Float) -> Unit,
    val onGlassStyle: (GlassStyle) -> Unit,
    val onAnimatedBackgrounds: (Boolean) -> Unit,
    val onWifiCalling: (Boolean) -> Unit,
    val onSystemCalling: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    deviceName: String,
    availability: EffectAvailability,
    batteryUnrestricted: Boolean,
    isSamsung: Boolean,
    wifiCallingSupported: Boolean,
    systemCallingSupported: Boolean,
    versionText: String,
    actions: SettingsActions,
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    GlassIconButton(
                        onClick = actions.onBack,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppearanceCard(settings, actions)

            GlassCard {
                CardTitle("Your name")
                NameField(settings.displayName, deviceName, actions.onName)
            }

            GlassCard {
                CardTitle("Noise cancellation")
                Text(
                    "Each one can be turned off on its own. Changes apply instantly, even during a call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NoiseToggles(settings.noise, availability, actions.onNoise)
            }

            GlassCard {
                CardTitle("Calls")
                ToggleRow(
                    title = "Wi-Fi calling",
                    subtitle = if (wifiCallingSupported) {
                        "Call nearby phones over direct Wi-Fi (Wi-Fi Aware) as well as Bluetooth. No router needed. " +
                            "Uses a little more battery while BlueCall is open."
                    } else {
                        "This phone doesn't support Wi-Fi Aware, so calls use Bluetooth."
                    },
                    checked = settings.wifiCalling,
                    enabled = wifiCallingSupported,
                    onChange = actions.onWifiCalling,
                )
                ToggleRow(
                    title = "Use system calling",
                    subtitle = if (systemCallingSupported) {
                        "Calls show up like phone calls, so earphones, car kits and watches can answer, hang up and " +
                            "take the audio as soon as they connect. Applies from the next call."
                    } else {
                        "This phone doesn't have Android's calling system; BlueCall routes audio itself."
                    },
                    checked = settings.systemCalling,
                    enabled = systemCallingSupported,
                    onChange = actions.onSystemCalling,
                )
            }

            GlassCard {
                CardTitle("Call quality")
                ToggleRow(
                    title = "Ultra-low latency",
                    subtitle = "10 ms audio frames: about 10 ms less delay for slightly more battery. Applies from the next call.",
                    checked = settings.ultraLowLatency,
                    enabled = true,
                    onChange = actions.onUltraLowLatency,
                )
            }

            GlassCard {
                CardTitle("Battery")
                Text("Go offline when idle", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                val choices = AppSettings.AUTO_OFFLINE_CHOICES
                GlassSegmentedRow(
                    options = choices.map { if (it == 0) "Never" else "$it min" },
                    selected = choices.indexOf(settings.autoOfflineMinutes).coerceAtLeast(0),
                    onSelect = { actions.onAutoOffline(choices[it]) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If no call happens for this long while BlueCall is in the background, it shuts down completely. " +
                        "It never wakes your phone to do this; it checks the next time the phone wakes up anyway.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                if (batteryUnrestricted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CallGreen)
                        Spacer(Modifier.width(8.dp))
                        Text("Battery use: Unrestricted. Calls ring while locked.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    GlassButton(
                        onClick = actions.onAllowBattery,
                        modifier = Modifier.fillMaxWidth(),
                        tint = MaterialTheme.colorScheme.primary,
                    ) {
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
            }

            GlassCard {
                CardTitle("About")
                Text(versionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCard(settings: AppSettings, actions: SettingsActions) {
    val theme = settings.theme
    val selectedPalette = Palettes.resolveId(theme.palette)
    val context = LocalContext.current
    val dark = LocalBlueCallLook.current.dark
    val dynamicColors = remember(dark) {
        val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        Palettes.liquidFrom(listOf(s.primary, s.tertiary, s.secondary, s.primaryContainer))
    }
    // The hue slider updates instantly on screen and is saved once the finger pauses.
    var hue by remember { mutableFloatStateOf(theme.customHue) }
    val saveHue by rememberUpdatedState(actions.onCustomHue)
    LaunchedEffect(hue) {
        if (hue != theme.customHue) {
            delay(150)
            saveHue(hue)
        }
    }

    GlassCard {
        CardTitle("Appearance")
        Label("Mode")
        val modes = ThemeMode.entries
        GlassSegmentedRow(
            options = listOf("System", "Light", "Dark"),
            selected = modes.indexOf(theme.mode),
            onSelect = { actions.onThemeMode(modes[it]) },
        )

        Label("Colours")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Palettes.choiceIds.forEach { id ->
                val colors = when (id) {
                    Palettes.DYNAMIC -> dynamicColors
                    Palettes.CUSTOM -> remember(hue) { Palettes.custom(hue).liquid }
                    else -> Palettes.preset(id)?.liquid ?: dynamicColors
                }
                PaletteSwatch(
                    name = Palettes.displayName(id),
                    colors = colors,
                    selected = id == selectedPalette,
                    onClick = { actions.onPalette(id) },
                )
            }
        }

        AnimatedVisibility(
            visible = selectedPalette == Palettes.CUSTOM,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column {
                Label("Custom colour")
                HueSlider(hue, onHue = { hue = it })
            }
        }

        Label("Glass")
        val styles = GlassStyle.entries
        GlassSegmentedRow(
            options = listOf("Clear", "Frosted", "Solid"),
            selected = styles.indexOf(theme.glass),
            onSelect = { actions.onGlassStyle(styles[it]) },
        )
        ToggleRow(
            title = "Animated backgrounds",
            subtitle = "Liquid colours flow behind the glass. Pauses automatically in Battery Saver.",
            checked = theme.animatedBackgrounds,
            enabled = true,
            onChange = actions.onAnimatedBackgrounds,
        )
    }
}

@Composable
private fun PaletteSwatch(name: String, colors: List<Color>, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (selected) 1.08f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "swatch",
    )
    val ring = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .width(64.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(colors + colors.first()))
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.40f), Color.Transparent)))
                    .border(
                        width = if (selected) 2.5.dp else 1.dp,
                        color = if (selected) ring else Color.White.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** A rainbow track with a glass thumb; tap or drag to pick any hue. */
@Composable
private fun HueSlider(hue: Float, onHue: (Float) -> Unit) {
    val rainbow = remember { Brush.horizontalGradient((0..6).map { Color.hsv((it * 60f) % 360f, 0.85f, 0.95f) }) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
        val thumb = 34.dp
        val trackWidth = maxWidth
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun hueAt(x: Float) = (x / widthPx * 360f).coerceIn(0f, 360f)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) { detectTapGestures { onHue(hueAt(it.x)) } }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onHue(hueAt(change.position.x))
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(rainbow)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (trackWidth - thumb) * (hue / 360f))
                    .size(thumb)
                    .clip(CircleShape)
                    .background(Color.hsv(hue.coerceIn(0f, 359.9f), 0.85f, 0.95f))
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent)))
                    .border(3.dp, Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun CardTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
