package com.codemew.bluecall

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.audio.NativeAudio
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.call.NearbyPeer
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.SettingsRepository
import com.codemew.bluecall.power.PowerPolicy
import com.codemew.bluecall.service.AppPermissions
import com.codemew.bluecall.service.BlueCall
import com.codemew.bluecall.service.BlueCallService
import com.codemew.bluecall.service.PresenceState
import com.codemew.bluecall.ui.HomeActions
import com.codemew.bluecall.ui.HomeScreen
import com.codemew.bluecall.ui.HomeUi
import com.codemew.bluecall.ui.PermissionsScreen
import com.codemew.bluecall.ui.SettingsActions
import com.codemew.bluecall.ui.SettingsScreen
import com.codemew.bluecall.ui.glass.GlassBackdrop
import com.codemew.bluecall.ui.theme.BlueCallTheme
import com.codemew.bluecall.ui.theme.LocalBlueCallLook
import com.codemew.bluecall.telecom.SystemCall
import com.codemew.bluecall.wifi.AwareSupport
import kotlinx.coroutines.launch

/**
 * Opening BlueCall puts this phone online (visible to nearby phones running
 * BlueCall). The Exit button takes it fully offline and closes the app.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /** Home and Settings drift slowly; the call screen flows at full speed. */
        const val HOME_LIQUID_SPEED = 0.25f
    }

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private var bluetoothOn by mutableStateOf(false)

    /** Wi-Fi Aware calling could go online right now (supported, permitted and available). */
    private var wifiReady = false
    private var permissionsOk by mutableStateOf(false)
    private var notificationsOk by mutableStateOf(true)
    private var fullScreenOk by mutableStateOf(true)
    private var batteryOk by mutableStateOf(false)
    private var receiverRegistered = false

    /** Bluetooth and Wi-Fi Aware state changes. Registered only while this screen is visible. */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshSystemState()
            maybeGoOnline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { BlueCall.exitEvents.collect { finishAndRemoveTask() } }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            BlueCallTheme(settings.theme) {
                val dark = LocalBlueCallLook.current.dark
                LaunchedEffect(dark) { applySystemBars(dark) }
                GlassBackdrop(speed = HOME_LIQUID_SPEED) { MainContent(settings) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BlueCall.activityStarted()
        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        refreshSystemState()
        maybeGoOnline()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        // Permissions or Location may have changed in system settings.
        BlueCall.controller?.refreshRadios()
        maybeGoOnline()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            receiverRegistered = false
        }
        BlueCall.activityStopped()
        super.onStop()
    }

    private fun refreshSystemState() {
        permissionsOk = AppPermissions.hasRequired(this)
        notificationsOk = AppPermissions.canPostNotifications(this)
        fullScreenOk = AppPermissions.canUseFullScreenIntent(this)
        batteryOk = PowerPolicy.isIgnoringBatteryOptimizations(this)
        bluetoothOn = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        wifiReady = AwareSupport.isSupported(this) && AwareSupport.hasPermission(this) && AwareSupport.isAvailable(this)
    }

    private fun maybeGoOnline() {
        if (permissionsOk && (bluetoothOn || wifiReady) && BlueCall.controller == null) {
            BlueCallService.start(this)
        }
    }

    private fun exitApp() {
        val controller = BlueCall.controller
        if (controller != null) {
            controller.exit() // stops everything, then closes every screen and ends the process
        } else {
            // Already offline: just close, and end the (idle) process too.
            finishAndRemoveTask()
            window.decorView.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(PowerPolicy.appDetailsIntent(this)) }
        } catch (_: SecurityException) {
            runCatching { startActivity(PowerPolicy.appDetailsIntent(this)) }
        }
    }

    private fun openNotificationSettings() {
        launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** Status and navigation bar icons that stay readable over the chosen light or dark glass. */
    private fun applySystemBars(dark: Boolean) {
        val transparent = AndroidColor.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private enum class Screen { PERMISSIONS, HOME, SETTINGS }

    @Composable
    private fun MainContent(settings: AppSettings) {
        val presence by BlueCall.presence.collectAsStateWithLifecycle()
        val peers by BlueCall.peers.collectAsStateWithLifecycle()
        val callState by BlueCall.callState.collectAsStateWithLifecycle()
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var askedPermissions by rememberSaveable { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            askedPermissions = true
            refreshSystemState()
            maybeGoOnline()
        }
        val bluetoothLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            refreshSystemState()
            maybeGoOnline()
        }
        var askedWifi by rememberSaveable { mutableStateOf(false) }
        val wifiPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            askedWifi = true
            refreshSystemState()
            BlueCall.controller?.refreshRadios()
            maybeGoOnline()
        }

        // An incoming call while BlueCall is on screen: show the call screen straight away.
        LaunchedEffect(callState) {
            if (callState is CallState.Incoming) startActivity(CallActivity.intent(this@MainActivity, answer = false))
        }

        BackHandler(enabled = showSettings && permissionsOk) { showSettings = false }

        val screen = when {
            !permissionsOk -> Screen.PERMISSIONS
            showSettings -> Screen.SETTINGS
            else -> Screen.HOME
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val spec = spring<IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                (slideInHorizontally(spec) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)))
                    .togetherWith(
                        slideOutHorizontally(spec) { w -> if (forward) -w / 4 else w / 4 } +
                            fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                    )
            },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.PERMISSIONS -> {
                    val permanentlyDenied = askedPermissions && AppPermissions.required.any {
                        !AppPermissions.granted(this@MainActivity, it) && !shouldShowRequestPermissionRationale(it)
                    }
                    PermissionsScreen(
                        showOpenSettings = permanentlyDenied,
                        onContinue = { permissionLauncher.launch(AppPermissions.required + AppPermissions.optional) },
                        onOpenSettings = { launch(PowerPolicy.appDetailsIntent(this@MainActivity)) },
                    )
                }

                Screen.SETTINGS -> SettingsContent(settings, onBack = { showSettings = false })
                Screen.HOME -> HomeContent(
                    settings = settings,
                    presence = presence,
                    peers = peers,
                    callState = callState,
                    onOpenSettings = { showSettings = true },
                    onEnableBluetooth = { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    onAllowWifi = {
                        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(AwareSupport.permission)
                        } else {
                            AppPermissions.wifiOnAndroid12
                        }
                        val blocked = askedWifi && !shouldShowRequestPermissionRationale(AwareSupport.permission)
                        if (blocked) launch(PowerPolicy.appDetailsIntent(this@MainActivity)) else wifiPermissionLauncher.launch(wanted)
                    },
                    onRequestNotifications = {
                        if (!askedPermissions) permissionLauncher.launch(AppPermissions.optional) else openNotificationSettings()
                    },
                )
            }
        }
    }

    @Composable
    private fun SettingsContent(settings: AppSettings, onBack: () -> Unit) {
        val availability = remember { EffectAvailability.query() }
        val versionText = remember {
            val opus = if (NativeAudio.ensureLoaded()) NativeAudio.opusVersion() else "Opus unavailable"
            "BlueCall ${BuildConfig.VERSION_NAME} · $opus · RNNoise 0.1.1"
        }
        fun save(block: suspend SettingsRepository.() -> Unit) {
            lifecycleScope.launch { settingsRepository.block() }
        }
        SettingsScreen(
            settings = settings,
            deviceName = SettingsRepository.deviceName(this),
            availability = availability,
            batteryUnrestricted = batteryOk,
            isSamsung = PowerPolicy.isSamsung,
            wifiCallingSupported = remember { AwareSupport.isSupported(this) },
            systemCallingSupported = remember { SystemCall.isSupported(this) },
            versionText = versionText,
            actions = SettingsActions(
                onBack = onBack,
                onName = { name -> save { setDisplayName(name) } },
                onNoise = { noise -> save { setNoise(noise) } },
                onUltraLowLatency = { on -> save { setUltraLowLatency(on) } },
                onAutoOffline = { minutes -> save { setAutoOfflineMinutes(minutes) } },
                onAllowBattery = { launch(PowerPolicy.requestIgnoreBatteryOptimizationsIntent(this)) },
                onSamsungBattery = { launch(PowerPolicy.samsungBatterySettingsIntent(this)) },
                onPalette = { id -> save { setThemePalette(id) } },
                onThemeMode = { mode -> save { setThemeMode(mode) } },
                onCustomHue = { hue -> save { setCustomHue(hue) } },
                onGlassStyle = { style -> save { setGlassStyle(style) } },
                onAnimatedBackgrounds = { on -> save { setAnimatedBackgrounds(on) } },
                onWifiCalling = { on -> save { setWifiCalling(on) } },
                onSystemCalling = { on -> save { setSystemCalling(on) } },
            ),
        )
    }

    @Composable
    private fun HomeContent(
        settings: AppSettings,
        presence: PresenceState,
        peers: List<NearbyPeer>,
        callState: CallState,
        onOpenSettings: () -> Unit,
        onEnableBluetooth: () -> Unit,
        onAllowWifi: () -> Unit,
        onRequestNotifications: () -> Unit,
    ) {
        HomeScreen(
            ui = HomeUi(
                presence = presence,
                peers = peers,
                lastEnd = (callState as? CallState.Idle)?.lastEnd,
                bluetoothOn = bluetoothOn,
                wifiNeedsLocationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
                notificationsAllowed = notificationsOk,
                fullScreenAllowed = fullScreenOk,
                batteryUnrestricted = batteryOk,
                batteryCardDismissed = settings.batteryCardDismissed,
                isSamsung = PowerPolicy.isSamsung,
            ),
            actions = HomeActions(
                onCall = { peer, transport ->
                    val controller = BlueCall.controller
                    if (controller != null) {
                        controller.dial(peer, transport)
                        startActivity(CallActivity.intent(this, answer = false))
                    }
                },
                onOpenSettings = onOpenSettings,
                onExit = ::exitApp,
                onEnableBluetooth = onEnableBluetooth,
                onEnableWifi = { launch(Intent(Settings.Panel.ACTION_WIFI)) },
                onAllowWifi = onAllowWifi,
                onLocationSettings = { launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                onAllowNotifications = onRequestNotifications,
                onAllowFullScreen = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        launch(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()),
                        )
                    }
                },
                onAllowBattery = { launch(PowerPolicy.requestIgnoreBatteryOptimizationsIntent(this)) },
                onDismissBattery = { lifecycleScope.launch { settingsRepository.setBatteryCardDismissed(true) } },
                onSamsungBattery = { launch(PowerPolicy.samsungBatterySettingsIntent(this)) },
            ),
        )
    }
}
