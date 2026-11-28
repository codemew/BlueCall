package com.codemew.bluecall.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object AppPermissions {
    /** Needed to go online: find, be found by and connect to nearby phones, and talk. */
    val required: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.RECORD_AUDIO,
    )

    /**
     * Asked for together with [required], but BlueCall still works without them.
     * On Android 13+ the Wi-Fi permission is part of the same "Nearby devices"
     * prompt; on Android 12 Wi-Fi calling needs Location, which is asked for
     * separately ([wifiOnAndroid12]) only if the user wants Wi-Fi calls.
     */
    val optional: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyArray()
        }

    /** What Wi-Fi calling needs on Android 12 (Wi-Fi Aware requires location there). */
    val wifiOnAndroid12: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasRequired(context: Context) = required.all { granted(context, it) }

    fun canPostNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
}
