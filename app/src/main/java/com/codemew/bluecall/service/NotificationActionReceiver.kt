package com.codemew.bluecall.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Receives taps on notification buttons (decline, hang up, mute, speaker, go offline).
 * Not exported and registered for no system broadcasts, so it only ever runs
 * because the user tapped something.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = BlueCall.controller
        if (controller == null) {
            // A leftover notification from a run that has already shut down.
            NotificationManagerCompat.from(context).apply {
                cancel(CallNotifications.ID_INCOMING)
                cancel(CallNotifications.ID_SERVICE)
            }
            return
        }
        when (intent.action) {
            CallNotifications.ACTION_EXIT -> controller.exit()
            CallNotifications.ACTION_DECLINE -> controller.decline()
            CallNotifications.ACTION_HANGUP -> controller.hangUp()
            CallNotifications.ACTION_TOGGLE_MUTE -> controller.toggleMute()
            CallNotifications.ACTION_TOGGLE_SPEAKER -> controller.toggleSpeaker()
        }
    }
}
