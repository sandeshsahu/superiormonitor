package com.system.superiormonitor.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

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

object TelemetryCollector {

    private suspend fun executeRootCommand(command: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = com.topjohnwu.superuser.Shell.cmd(command).exec()
            result.out.joinToString("\n").trim()
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun gatherTelemetry(): Map<String, String> {
        val metrics = mutableMapOf<String, String>()

        metrics["TEMP"] = getTemperature()
        metrics["CPU_FREQ_MHZ"] = getCpuFreq()
        metrics["CPU_LOAD"] = getCpuLoad()
        metrics["STORAGE"] = getStorageUsed()
        
        val batteryDetails = getBatteryDetails()
        metrics["BATTERY"] = batteryDetails["capacity"] ?: "N/A"
        metrics["STATUS"] = batteryDetails["status"] ?: "N/A"
        
        val signals = getCellularSignal()
        metrics["SIM1_RSRP"] = signals["sim1"] ?: "N/A"
        metrics["SIM2_RSRP"] = signals["sim2"] ?: "N/A"
        
        val carriers = getCarrierNames()
        metrics["SIM1_CARRIER"] = carriers["sim1"] ?: "N/A"
        metrics["SIM2_CARRIER"] = carriers["sim2"] ?: "N/A"
        
        metrics["GATEWAY_IP"] = getGatewayIp()
        metrics["NETWORK_IP"] = getNetworkIp()
        metrics["UPTIME"] = getUptime()
        metrics["CURRENT_TIME"] = getCurrentTime()

        return metrics
    }

    private suspend fun getCarrierNames(): Map<String, String> {
        val result = mutableMapOf("sim1" to "N/A", "sim2" to "N/A")
        try {
            val output = executeRootCommand("getprop gsm.operator.alpha")
            val parts = output.split(",")
            if (parts.isNotEmpty()) {
                val sim1 = parts[0].trim()
                if (sim1.isNotBlank()) result["sim1"] = sim1
            }
            if (parts.size > 1) {
                val sim2 = parts[1].trim()
                if (sim2.isNotBlank()) result["sim2"] = sim2
            }
        } catch (e: Exception) {
        }
        return result
    }

    private suspend fun getTemperature(): String {
        return try {
            val tempStr = executeRootCommand("cat /sys/class/thermal/thermal_zone0/temp")
            if (tempStr.isNotBlank()) {
                String.format(Locale.US, "%.1f", tempStr.toFloat() / 1000.0)
            } else {
                val battTempStr = executeRootCommand("cat /sys/class/power_supply/battery/temp")
                if (battTempStr.isNotBlank()) {
                    String.format(Locale.US, "%.1f", battTempStr.toFloat() / 10.0)
                } else "N/A"
            }
        } catch (e: Exception) {
            "N/A"
        }
    }

    private suspend fun getCpuFreq(): String {
        return try {
            val freqStr = executeRootCommand("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
            if (freqStr.isNotBlank()) {
                (freqStr.toLong() / 1000).toString()
            } else "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private suspend fun getCpuLoad(): String {
        return try {
            val loadAvg = executeRootCommand("cat /proc/loadavg")
            loadAvg.split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private suspend fun getStorageUsed(): String {
        return try {
            val output = executeRootCommand("df -h /data")
            val lines = output.trim().split("\n")
            if (lines.size > 1) {
                val tokens = lines[1].trim().split(Regex("\\s+"))
                if (tokens.size >= 5) tokens[4] else "N/A"
            } else "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private suspend fun getBatteryDetails(): Map<String, String> {
        return try {
            var capacity = executeRootCommand("cat /sys/class/power_supply/battery/capacity")
            if (capacity.isBlank()) capacity = "N/A"
            
            var status = executeRootCommand("cat /sys/class/power_supply/battery/status")
            if (status.isBlank()) status = "N/A"

            mapOf("capacity" to capacity, "status" to status)
        } catch (e: Exception) {
            mapOf("capacity" to "N/A", "status" to "N/A")
        }
    }

    private suspend fun getGatewayIp(): String {
        return try {
            val output = executeRootCommand("ip route")
            val ap0Route = output.split("\n").firstOrNull { it.contains("dev ap0") }
            if (ap0Route != null) {
                val match = Regex("([0-9]{1,3}\\.){3}[0-9]{1,3}").find(ap0Route)
                match?.value ?: "Disconnected"
            } else {
                "Disconnected"
            }
        } catch (e: Exception) {
            "Disconnected"
        }
    }

    private suspend fun getNetworkIp(): String {
        return try {
            val output = executeRootCommand("ip addr show")
            val lines = output.split("\n")
            var currentInterface = ""
            for (line in lines) {
                // Match lines like "2: wlan0: <BROADCAST...>" or "4: rmnet_data0: <..."
                if (line.matches(Regex("^[0-9]+: .*:.*"))) {
                    val iface = line.substringAfter(": ").substringBefore(":").substringBefore("@").trim()
                    if (iface != "lo" && iface != "dummy0") {
                        currentInterface = iface
                    } else {
                        currentInterface = ""
                    }
                }
                if (currentInterface.isNotBlank() && line.trim().startsWith("inet ")) {
                    val ip = line.trim().split(" ")[1].split("/")[0]
                    if (ip != "127.0.0.1") return ip
                }
            }
            "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private suspend fun getCellularSignal(): Map<String, String> {
        val result = mutableMapOf("sim1" to "N/A", "sim2" to "N/A")
        try {
            val output = executeRootCommand("dumpsys telephony.registry")
            val regex = Regex("rsrp=(-[0-9]*)")
            val matches = regex.findAll(output).toList()
            if (matches.isNotEmpty()) {
                result["sim1"] = matches[0].groupValues[1]
                if (matches.size > 1) {
                    result["sim2"] = matches[1].groupValues[1]
                }
            }
        } catch (e: Exception) {
        }
        return result
    }

    private suspend fun getUptime(): String {
        return try {
            val seconds = android.os.SystemClock.elapsedRealtime() / 1000f
            if (seconds >= 86400) {
                String.format(Locale.US, "%.1f days", seconds / 86400f)
            } else {
                String.format(Locale.US, "%.1f hours", seconds / 3600f)
            }
        } catch (e: Exception) {
            "N/A"
        }
    }

    private suspend fun getCurrentTime(): String {
        return try {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            "N/A"
        }
    }
}
