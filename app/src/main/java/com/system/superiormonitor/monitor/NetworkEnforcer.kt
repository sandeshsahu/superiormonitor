package com.system.superiormonitor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class NetworkEnforcer(private val context: Context, private val prefsManager: PrefsManager) {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // ContentObserver for Mobile Data (reliably triggers when OS turns it off via Settings/QuickSettings)
    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val isDataEnabled = Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
            if (!isDataEnabled && prefsManager.persistentEnforcementEnabled && prefsManager.forceMobileData) {
                CoroutineScope(Dispatchers.IO).launch {
                    LogManager.log(LogCategory.NETWORK, "[ENFORCER] Mobile Data disable detected. Forcing re-enable...")
                    delay(3000)
                    enforceRootCommand("svc data enable", "Mobile Data")
                }
            }
        }
    }

    // Zero-polling BroadcastReceiver for Wi-Fi and Hotspot changes
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            CoroutineScope(Dispatchers.IO).launch {
                when (action) {
                    "android.net.wifi.WIFI_STATE_CHANGED" -> {
                        val state = intent.getIntExtra("wifi_state", 1) // 1 = WIFI_STATE_DISABLED
                        if (state == 1 && prefsManager.persistentEnforcementEnabled && prefsManager.forceWifi) {
                            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Wi-Fi disable detected via broadcast. Forcing re-enable...")
                            delay(3000)
                            enforceRootCommand("svc wifi enable", "Wi-Fi")
                        }
                    }
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        val state = intent.getIntExtra("wifi_state", 11) // 11 = WIFI_AP_STATE_DISABLED
                        if (state == 11 && prefsManager.persistentEnforcementEnabled && prefsManager.forceHotspot) {
                            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Hotspot disable detected. Forcing re-enable via Reflection...")
                            delay(3000)
                            enforceHotspot(context)
                        }
                    }
                }
            }
        }
    }

    private suspend fun enforceRootCommand(cmd: String, label: String) {
        try {
            withTimeout(5000) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                process.waitFor()
            }
            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Successfully re-enabled $label")
        } catch (e: TimeoutCancellationException) {
            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Root command for $label timed out. Preventing hang.")
        } catch (e: Exception) {
            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Error re-enabling $label: ${e.message}")
        }
    }

    private suspend fun enforceHotspot(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method = cm::class.java.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            )
            method.invoke(cm, 0, false, null)
            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Successfully re-enabled Hotspot (Legacy API)")
        } catch (e: Exception) {
            try {
                val tm = context.getSystemService("tethering")
                if (tm != null) {
                    val methods = tm::class.java.declaredMethods
                    var invoked = false
                    val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                    val proxyCallback = java.lang.reflect.Proxy.newProxyInstance(
                        context.classLoader,
                        arrayOf(callbackClass)
                    ) { _, _, _ -> null }

                    for (m in methods) {
                        if (m.name == "startTethering") {
                            m.isAccessible = true
                            val params = m.parameterTypes
                            try {
                                if (params.size == 3 && params[0].name.contains("TetheringRequest")) {
                                    val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                                    val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
                                    val request = builderClass.getMethod("build").invoke(builder)
                                    val executor = java.util.concurrent.Executor { it.run() }
                                    m.invoke(tm, request, executor, proxyCallback)
                                    LogManager.log(LogCategory.NETWORK, "[ENFORCER] Successfully re-enabled Hotspot (TM TetheringRequest API)")
                                    invoked = true
                                    break
                                } else if (params.size == 3 && params[0] == Int::class.javaPrimitiveType) {
                                    val executor = java.util.concurrent.Executor { it.run() }
                                    m.invoke(tm, 0, executor, proxyCallback)
                                    LogManager.log(LogCategory.NETWORK, "[ENFORCER] Successfully re-enabled Hotspot (TM Int API)")
                                    invoked = true
                                    break
                                }
                            } catch (innerE: Exception) {
                                LogManager.log(LogCategory.NETWORK, "[ENFORCER] TM invoke error: ${innerE.message}")
                            }
                        }
                    }
                    if (!invoked) {
                        LogManager.log(LogCategory.NETWORK, "[ENFORCER] No matching startTethering method found in TetheringManager.")
                    }
                }
            } catch (e2: Exception) {
                LogManager.log(LogCategory.NETWORK, "[ENFORCER] Error re-enabling Hotspot via Reflection: ${e2.message}")
            }
        }
    }

    fun start() {
        if (!isRunning) {
            // Register ContentObserver for data settings changes
            context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, contentObserver)

            // Register BroadcastReceiver for Wi-Fi and Hotspot events
            val filter = IntentFilter().apply {
                addAction("android.net.wifi.WIFI_STATE_CHANGED")
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
            context.registerReceiver(broadcastReceiver, filter)

            isRunning = true
            LogManager.log(LogCategory.NETWORK, "Network Enforcer started.")
            evaluateStateOnBoot()
        }
    }

    private fun evaluateStateOnBoot() {
        if (!prefsManager.persistentEnforcementEnabled) return
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2000) // Small delay to let system settle after boot/start
            
            // Check Mobile Data
            if (prefsManager.forceMobileData) {
                try {
                    val mobileDataEnabled = Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
                    if (!mobileDataEnabled) {
                        LogManager.log(LogCategory.NETWORK, "[ENFORCER] Mobile Data is OFF on start. Enforcing...")
                        enforceRootCommand("svc data enable", "Mobile Data")
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.NETWORK, "[ENFORCER] Fatal error reading Mobile Data state. Disabling feature to prevent crashes.")
                    prefsManager.forceMobileData = false
                }
            }

            // Check Wi-Fi
            if (prefsManager.forceWifi) {
                try {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    if (!wifiManager.isWifiEnabled) {
                        LogManager.log(LogCategory.NETWORK, "[ENFORCER] Wi-Fi is OFF on start. Enforcing...")
                        enforceRootCommand("svc wifi enable", "Wi-Fi")
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.NETWORK, "[ENFORCER] Fatal error reading Wi-Fi state. Disabling feature to prevent crashes.")
                    prefsManager.forceWifi = false
                }
            }

            // Check Hotspot
            if (prefsManager.forceHotspot) {
                try {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                    method.isAccessible = true
                    val isApEnabled = method.invoke(wifiManager) as Boolean
                    if (!isApEnabled) {
                        LogManager.log(LogCategory.NETWORK, "[ENFORCER] Hotspot is OFF on start. Enforcing...")
                        enforceHotspot(context)
                    }
                } catch (e: Exception) {
                    // Fallback to checking network interfaces
                    try {
                        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                        var isUp = false
                        for (iface in interfaces) {
                            if (iface.name == "ap0" && iface.isUp) {
                                isUp = true
                                break
                            }
                        }
                        if (!isUp) {
                            LogManager.log(LogCategory.NETWORK, "[ENFORCER] Hotspot (ap0) is OFF on start. Enforcing...")
                            enforceHotspot(context)
                        }
                    } catch (e2: Exception) {
                        LogManager.log(LogCategory.NETWORK, "[ENFORCER] Fatal error reading Hotspot state. Disabling feature to prevent crashes.")
                        prefsManager.forceHotspot = false
                    }
                }
            }
        }
    }

    fun stop() {
        if (isRunning) {
            context.contentResolver.unregisterContentObserver(contentObserver)
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (e: Exception) {}
            isRunning = false
            LogManager.log(LogCategory.NETWORK, "Network Enforcer stopped.")
        }
    }
}
