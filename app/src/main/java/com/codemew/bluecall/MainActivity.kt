package com.codemew.bluecall

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.audio.NativeAudio
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.SettingsRepository
import com.codemew.bluecall.power.PowerPolicy
import com.codemew.bluecall.service.AppPermissions
import com.codemew.bluecall.service.BlueCall
import com.codemew.bluecall.service.BlueCallService
import com.codemew.bluecall.ui.HomeActions
import com.codemew.bluecall.ui.HomeScreen
import com.codemew.bluecall.ui.HomeUi
import com.codemew.bluecall.ui.PermissionsScreen
import com.codemew.bluecall.ui.SettingsActions
import com.codemew.bluecall.ui.SettingsScreen
import com.codemew.bluecall.ui.theme.BlueCallTheme
import kotlinx.coroutines.launch

/**
 * Opening BlueCall puts this phone online (visible to nearby phones running
 * BlueCall). The Exit button takes it fully offline and closes the app.
 */
class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private var bluetoothOn by mutableStateOf(false)
    private var permissionsOk by mutableStateOf(false)
    private var notificationsOk by mutableStateOf(true)
    private var fullScreenOk by mutableStateOf(true)
    private var batteryOk by mutableStateOf(false)
    private var receiverRegistered = false

    /** Registered only while this screen is visible. */
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
        setContent { BlueCallTheme { MainContent() } }
    }

    override fun onStart() {
        super.onStart()
        BlueCall.activityStarted()
        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        refreshSystemState()
        maybeGoOnline()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
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
    }

    private fun maybeGoOnline() {
        if (permissionsOk && bluetoothOn && BlueCall.controller == null) {
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

    @Composable
    private fun MainContent() {
        val presence by BlueCall.presence.collectAsStateWithLifecycle()
        val peers by BlueCall.peers.collectAsStateWithLifecycle()
        val callState by BlueCall.callState.collectAsStateWithLifecycle()
        val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
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

        // An incoming call while BlueCall is on screen: show the call screen straight away.
        LaunchedEffect(callState) {
            if (callState is CallState.Incoming) startActivity(CallActivity.intent(this@MainActivity, answer = false))
        }

        if (!permissionsOk) {
            val permanentlyDenied = askedPermissions && AppPermissions.required.any {
                !AppPermissions.granted(this, it) && !shouldShowRequestPermissionRationale(it)
            }
            PermissionsScreen(
                showOpenSettings = permanentlyDenied,
                onContinue = { permissionLauncher.launch(AppPermissions.required + AppPermissions.optional) },
                onOpenSettings = { launch(PowerPolicy.appDetailsIntent(this)) },
            )
            return
        }

        BackHandler(enabled = showSettings) { showSettings = false }

        if (showSettings) {
            val availability = remember { EffectAvailability.query() }
            val versionText = remember {
                val opus = if (NativeAudio.ensureLoaded()) NativeAudio.opusVersion() else "Opus unavailable"
                "BlueCall ${BuildConfig.VERSION_NAME} · $opus · RNNoise 0.1.1"
            }
            SettingsScreen(
                settings = settings,
                deviceName = SettingsRepository.deviceName(this),
                availability = availability,
                batteryUnrestricted = batteryOk,
                isSamsung = PowerPolicy.isSamsung,
                versionText = versionText,
                actions = SettingsActions(
                    onBack = { showSettings = false },
                    onName = { name -> lifecycleScope.launch { settingsRepository.setDisplayName(name) } },
                    onNoise = { noise -> lifecycleScope.launch { settingsRepository.setNoise(noise) } },
                    onUltraLowLatency = { on -> lifecycleScope.launch { settingsRepository.setUltraLowLatency(on) } },
                    onAutoOffline = { minutes -> lifecycleScope.launch { settingsRepository.setAutoOfflineMinutes(minutes) } },
                    onAllowBattery = { launch(PowerPolicy.requestIgnoreBatteryOptimizationsIntent(this)) },
                    onSamsungBattery = { launch(PowerPolicy.samsungBatterySettingsIntent(this)) },
                ),
            )
            return
        }

        HomeScreen(
            ui = HomeUi(
                presence = presence,
                peers = peers,
                lastEnd = (callState as? CallState.Idle)?.lastEnd,
                bluetoothOn = bluetoothOn,
                notificationsAllowed = notificationsOk,
                fullScreenAllowed = fullScreenOk,
                batteryUnrestricted = batteryOk,
                batteryCardDismissed = settings.batteryCardDismissed,
                isSamsung = PowerPolicy.isSamsung,
            ),
            actions = HomeActions(
                onCall = { peer ->
                    val controller = BlueCall.controller
                    if (controller != null) {
                        controller.dial(peer)
                        startActivity(CallActivity.intent(this, answer = false))
                    }
                },
                onOpenSettings = { showSettings = true },
                onExit = ::exitApp,
                onEnableBluetooth = { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                onAllowNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedPermissions) {
                        permissionLauncher.launch(AppPermissions.optional)
                    } else {
                        launch(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                        )
                    }
                },
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
