package com.codemew.bluecall.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.codemew.bluecall.CallActivity
import com.codemew.bluecall.MainActivity
import com.codemew.bluecall.R
import com.codemew.bluecall.call.PeerInfo
import com.codemew.bluecall.call.Transport

/**
 * All of BlueCall's notifications.
 *
 * - One foreground-service notification (ID_SERVICE), updated for each state:
 *   a quiet "BlueCall is on" while idle, a CallStyle call notification with a
 *   timer and controls while calling. CallStyle gives the status-bar call chip,
 *   full controls on the lock screen, and (Android 16+, One UI 8) a Live Update
 *   in the Now Bar.
 * - A separate ringing notification (ID_INCOMING) with a full-screen intent, so
 *   cancelling it always stops the ringtone.
 */
class CallNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    fun online(name: String): Notification =
        NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bluecall)
            .setContentTitle(context.getString(R.string.notif_online_title))
            .setContentText(context.getString(R.string.notif_online_text, name))
            .setContentIntent(openMain())
            .addAction(0, context.getString(R.string.notif_go_offline), action(ACTION_EXIT, 1))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun outgoing(peer: PeerInfo, ringing: Boolean): Notification =
        callBuilder(peer)
            .setContentText(context.getString(if (ringing) R.string.notif_ringing else R.string.notif_calling))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(peer), action(ACTION_HANGUP, 2)))
            .build()

    fun ongoing(
        peer: PeerInfo,
        connectedAtMs: Long,
        muted: Boolean,
        speakerOn: Boolean,
        onHold: Boolean,
        canResume: Boolean,
    ): Notification =
        callBuilder(peer)
            .setContentText(
                if (onHold) context.getString(R.string.notif_on_hold) else context.getString(R.string.notif_in_call, transportName(peer)),
            )
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(peer), action(ACTION_HANGUP, 2)))
            .addAction(
                0,
                context.getString(if (muted) R.string.notif_unmute else R.string.notif_mute),
                action(ACTION_TOGGLE_MUTE, 3),
            )
            .apply {
                if (canResume) {
                    addAction(0, context.getString(R.string.notif_resume), action(ACTION_RESUME, 5))
                } else {
                    addAction(
                        0,
                        context.getString(if (speakerOn) R.string.notif_earpiece else R.string.notif_speaker),
                        action(ACTION_TOGGLE_SPEAKER, 4),
                    )
                }
            }
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(connectedAtMs)
            .build()

    private fun callBuilder(peer: PeerInfo) =
        NotificationCompat.Builder(context, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_bluecall)
            .setContentTitle(peer.name)
            .setContentIntent(openCall(answer = false, requestCode = 20))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Not colorized: Android 16 only promotes uncolorized notifications to Live Updates (Now Bar).
            .setColor(ContextCompat.getColor(context, R.color.call_green))
            .setRequestPromotedOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    @SuppressLint("MissingPermission") // checked by canPostNotifications()
    fun showIncoming(peer: PeerInfo, timeoutMs: Long) {
        if (!AppPermissions.canPostNotifications(context)) return
        val fullScreen = openCall(answer = false, requestCode = 21)
        val notification = NotificationCompat.Builder(context, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_bluecall)
            .setContentTitle(peer.name)
            .setContentText(context.getString(R.string.notif_incoming_text, transportName(peer)))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(peer),
                    action(ACTION_DECLINE, 5),
                    openCall(answer = true, requestCode = 22),
                ),
            )
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(timeoutMs)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(ID_INCOMING, notification)
    }

    fun cancelIncoming() = manager.cancel(ID_INCOMING)

    @SuppressLint("MissingPermission")
    fun showMissed(peer: PeerInfo) {
        if (!AppPermissions.canPostNotifications(context)) return
        manager.notify(
            ID_MISSED,
            NotificationCompat.Builder(context, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat_bluecall)
                .setContentTitle(context.getString(R.string.notif_missed_title))
                .setContentText(context.getString(R.string.notif_missed_text, peer.name))
                .setContentIntent(openMain())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun showWentOffline(minutes: Int) {
        if (!AppPermissions.canPostNotifications(context)) return
        manager.notify(
            ID_OFFLINE,
            NotificationCompat.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_bluecall)
                .setContentTitle(context.getString(R.string.notif_offline_title))
                .setContentText(context.resources.getQuantityString(R.plurals.notif_offline_text, minutes, minutes))
                .setContentIntent(openMain())
                .setAutoCancel(true)
                .setSilent(true)
                .setTimeoutAfter(6 * 60 * 60 * 1000L)
                .build(),
        )
    }

    /** Replaces the foreground-service notification's content. */
    @SuppressLint("MissingPermission")
    fun updateService(notification: Notification) {
        if (!AppPermissions.canPostNotifications(context)) return
        manager.notify(ID_SERVICE, notification)
    }

    private fun transportName(peer: PeerInfo) = context.getString(
        if (peer.transport == Transport.WIFI) R.string.transport_wifi else R.string.transport_bluetooth,
    )

    private fun person(peer: PeerInfo) = Person.Builder().setName(peer.name).setImportant(true).build()

    private fun openMain(): PendingIntent = PendingIntent.getActivity(
        context, 30,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openCall(answer: Boolean, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        CallActivity.intent(context, answer),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, NotificationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val status = NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_status_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val incoming = NotificationChannel(CH_INCOMING, context.getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_incoming_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        val call = NotificationChannel(CH_CALL, context.getString(R.string.channel_call), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_call_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        val missed = NotificationChannel(CH_MISSED, context.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_missed_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(status, incoming, call, missed))
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_INCOMING = "incoming_call"
        const val CH_CALL = "ongoing_call"
        const val CH_MISSED = "missed_call"

        const val ID_SERVICE = 1
        const val ID_INCOMING = 2
        const val ID_MISSED = 3
        const val ID_OFFLINE = 4

        const val ACTION_EXIT = "com.codemew.bluecall.action.EXIT"
        const val ACTION_DECLINE = "com.codemew.bluecall.action.DECLINE"
        const val ACTION_HANGUP = "com.codemew.bluecall.action.HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.codemew.bluecall.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.codemew.bluecall.action.TOGGLE_SPEAKER"
        const val ACTION_RESUME = "com.codemew.bluecall.action.RESUME"
    }
}
