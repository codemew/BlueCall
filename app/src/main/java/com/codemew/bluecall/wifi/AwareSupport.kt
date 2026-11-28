package com.codemew.bluecall.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Whether this phone can make Wi-Fi Aware calls, and what that needs. */
object AwareSupport {
    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

    /** Android 13+: "Nearby devices" (Wi-Fi). Android 12: precise location. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(context: Context): Boolean =
        context.getSystemService(WifiAwareManager::class.java)?.isAvailable == true
}
