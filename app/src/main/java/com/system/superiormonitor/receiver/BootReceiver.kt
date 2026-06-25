package com.system.superiormonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.bot.BotService
import com.system.superiormonitor.util.LogManager
import com.system.superiormonitor.util.LogCategory

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager.getInstance(context)
            LogManager.log(LogCategory.CORE, "Boot completed event received.")
            if (prefs.isServiceEnabled) {
                LogManager.log(LogCategory.CORE, "Starting background service from boot...")
                val serviceIntent = Intent(context, BotService::class.java).apply {
                    putExtra("from_boot", true)
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    LogManager.log(LogCategory.ERROR, "Failed to start service from boot: ${e.message}")
                }
            }
        }
    }
}
