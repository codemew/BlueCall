package com.codemew.bluecall

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.codemew.bluecall.audio.EffectAvailability
import com.codemew.bluecall.call.isCallInProgress
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.SettingsRepository
import com.codemew.bluecall.service.BlueCall
import com.codemew.bluecall.ui.CallActions
import com.codemew.bluecall.ui.CallScreen
import com.codemew.bluecall.ui.theme.BlueCallTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The call screen. It can show over the lock screen and turn the screen on,
 * so an incoming call can be answered without unlocking. It closes itself
 * when the call ends.
 */
class CallActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The call screen is always dark glass over vivid liquid colours: keep bar icons light.
        val transparent = AndroidColor.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))

        lifecycleScope.launch { BlueCall.exitEvents.collect { finishAndRemoveTask() } }
        lifecycleScope.launch {
            var sawCall = false
            BlueCall.callState.collect { state ->
                if (state.isCallInProgress) {
                    sawCall = true
                } else {
                    if (sawCall) delay(END_LINGER_MS)
                    finish()
                }
            }
        }

        handleIntent(intent)

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            BlueCallTheme(settings.theme, forceDark = true) {
                val state by BlueCall.callState.collectAsStateWithLifecycle()
                val ui by BlueCall.callUi.collectAsStateWithLifecycle()
                val availability = remember { EffectAvailability.query() }
                CallScreen(
                    state = state,
                    ui = ui,
                    noise = settings.noise,
                    availability = availability,
                    actions = CallActions(
                        onAnswer = { BlueCall.controller?.answer() },
                        onDecline = { BlueCall.controller?.decline() },
                        onHangUp = { BlueCall.controller?.hangUp() },
                        onMute = { BlueCall.controller?.toggleMute() },
                        onSpeaker = { BlueCall.controller?.toggleSpeaker() },
                        onRoute = { BlueCall.controller?.selectRoute(it) },
                        onNoise = { noise -> lifecycleScope.launch { settingsRepository.setNoise(noise) } },
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        BlueCall.activityStarted()
    }

    override fun onStop() {
        BlueCall.activityStopped()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) {
            intent.removeExtra(EXTRA_ANSWER)
            // This activity is visible now, so the microphone can be started for the call.
            BlueCall.controller?.answer()
        }
    }

    companion object {
        private const val EXTRA_ANSWER = "com.codemew.bluecall.extra.ANSWER"
        private const val END_LINGER_MS = 1_200L

        fun intent(context: Context, answer: Boolean): Intent =
            Intent(context, CallActivity::class.java)
                .putExtra(EXTRA_ANSWER, answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
