package com.system.superiormonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.system.superiormonitor.MainActivity
import com.system.superiormonitor.util.LogLevel
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager

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
