package com.system.superiormonitor.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

enum class LogCategory(val displayName: String) {
    SYSTEM("System"),
    ENFORCEMENT("Enforcement"),
    SNAPSHOTS("Snapshots"),
    BASIC_UPDATE("Basic Update"),
    SOCIAL_UPDATE("Social Update"),
    BOT_ACTIVITY("Bot Activity"),
    ERROR("Error")
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String
)

object LogManager {
    private const val MAX_LOGS_PER_CATEGORY = 100

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }

    private val _isTelegramApiReachable = MutableStateFlow(false)
    val isTelegramApiReachable: StateFlow<Boolean> = _isTelegramApiReachable

    fun setTelegramApiReachable(isReachable: Boolean) {
        _isTelegramApiReachable.value = isReachable
    }

    private val logQueues = LogCategory.entries.associateWith { ArrayDeque<LogEntry>() }
    private val _logFlows = LogCategory.entries.associateWith { MutableStateFlow<List<LogEntry>>(emptyList()) }

    fun getLogs(category: LogCategory): StateFlow<List<LogEntry>> = _logFlows[category]!!

    fun log(category: LogCategory, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        
        synchronized(logQueues) {
            val queue = logQueues[category]!!
            if (queue.size >= MAX_LOGS_PER_CATEGORY) {
                queue.removeFirst()
            }
            queue.addLast(entry)
            
            // To trigger flow emission, we must provide a new list instance
            _logFlows[category]!!.value = queue.toList()
            
            // Mirror error logs to the ERROR category queue
            if (level == LogLevel.ERROR && category != LogCategory.ERROR) {
                val errQueue = logQueues[LogCategory.ERROR]!!
                if (errQueue.size >= MAX_LOGS_PER_CATEGORY) errQueue.removeFirst()
                errQueue.addLast(entry)
                _logFlows[LogCategory.ERROR]!!.value = errQueue.toList()
            }
        }
        
        // Also log to console for debugging
        when (level) {
            LogLevel.ERROR -> android.util.Log.e("SuperiorMonitor", "[${category.name}] $message")
            LogLevel.WARN -> android.util.Log.w("SuperiorMonitor", "[${category.name}] $message")
            LogLevel.DEBUG -> android.util.Log.d("SuperiorMonitor", "[${category.name}] $message")
            LogLevel.INFO -> android.util.Log.i("SuperiorMonitor", "[${category.name}] $message")
        }
    }

    fun clearLogs(category: LogCategory) {
        synchronized(logQueues) {
            logQueues[category]!!.clear()
            _logFlows[category]!!.value = emptyList()
        }
    }
}
