package com.codemew.bluecall.power

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Every power-related decision in one place.
 *
 * - A partial wakelock is held only while a call is being set up or is in
 *   progress, always with a timeout as a safety net.
 * - The auto-offline check uses a NON-wakeup alarm: if the phone is asleep
 *   when it falls due, it waits until the phone wakes up for some other
 *   reason. BlueCall itself never wakes the phone while idle.
 */
class PowerPolicy(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val callLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlueCall:call").apply {
            setReferenceCounted(false)
        }

    /** Short-lived lock so an incoming connection can be read before the phone sleeps again. */
    private val briefLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlueCall:handshake").apply {
            setReferenceCounted(false)
        }

    private var idleListener: AlarmManager.OnAlarmListener? = null

    val isCallLockHeld: Boolean get() = callLock.isHeld

    fun acquireCallLock(timeoutMs: Long) {
        callLock.acquire(timeoutMs)
    }

    fun releaseCallLock() {
        if (callLock.isHeld) callLock.release()
    }

    fun acquireBriefly(timeoutMs: Long) {
        briefLock.acquire(timeoutMs)
    }

    fun releaseBrief() {
        if (briefLock.isHeld) briefLock.release()
    }

    /** Schedules [onDue] at [elapsedRealtimeMs] without ever waking the phone for it. */
    fun scheduleIdleCheck(elapsedRealtimeMs: Long, onDue: () -> Unit) {
        cancelIdleCheck()
        val listener = AlarmManager.OnAlarmListener {
            idleListener = null
            onDue()
        }
        idleListener = listener
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, elapsedRealtimeMs, "BlueCall:idle", listener, handler)
    }

    fun cancelIdleCheck() {
        idleListener?.let { alarmManager.cancel(it) }
        idleListener = null
    }

    fun releaseAll() {
        releaseCallLock()
        releaseBrief()
        cancelIdleCheck()
    }

    companion object {
        val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        fun isIgnoringBatteryOptimizations(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        /** System dialog that sets battery usage to "Unrestricted" for BlueCall. */
        @SuppressLint("BatteryLife") // A calling app that must ring while locked is an accepted use case.
        fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

        /**
         * Samsung's battery screen ("Background usage limits" → "Never sleeping apps"),
         * falling back to the app's own settings page. Returns the first one that resolves.
         */
        fun samsungBatterySettingsIntent(context: Context): Intent {
            val candidates = listOf(
                Intent().setComponent(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ),
                Intent().setComponent(
                    ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ),
            )
            val pm = context.packageManager
            return candidates.firstOrNull { it.resolveActivity(pm) != null } ?: appDetailsIntent(context)
        }

        fun appDetailsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    }
}
