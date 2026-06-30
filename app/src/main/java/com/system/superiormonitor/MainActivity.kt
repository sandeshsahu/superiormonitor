package com.system.superiormonitor

import android.Manifest
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogLevel
import com.system.superiormonitor.util.LogManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.system.superiormonitor.theme.Background
import com.system.superiormonitor.theme.SuperiorMonitorTheme
import com.system.superiormonitor.ui.AppScreen
import com.system.superiormonitor.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = java.io.File(getExternalFilesDir(null), "error_alrt/offline")
                if (!crashDir.exists()) crashDir.mkdirs()
                val crashFile = java.io.File(crashDir, "offline_crash.txt")
                
                val crashLog = StringBuilder().apply {
                    appendLine("#Error")
                    appendLine("===================")
                    appendLine("⚠️ *Uncaught Exception*")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine("Stacktrace:")
                    appendLine(android.util.Log.getStackTraceString(throwable))
                    appendLine()
                }
                
                // Synchronously write to file before the process dies
                java.io.FileOutputStream(crashFile, true).use { fos ->
                    fos.write(crashLog.toString().toByteArray())
                }
            } catch (e: Exception) {
                android.util.Log.e("SystemCoreCrash", "Failed to write crash log synchronously", e)
            }
            
            LogManager.log(LogCategory.SYSTEM, "Uncaught exception on thread ${thread.name}: ${throwable.message}", LogLevel.ERROR)
            android.util.Log.e("SystemCoreCrash", "Uncaught exception on thread ${thread.name}", throwable)
            
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                kotlin.system.exitProcess(2)
            }
        }
        super.onCreate(savedInstanceState)
        LogManager.log(LogCategory.SYSTEM, "MainActivity UI Initialized")
        enableEdgeToEdge()
        setContent {
            SuperiorMonitorTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    AppScreen(
                        viewModel = viewModel,
                        requestPostNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                LogManager.log(LogCategory.SYSTEM, "Requesting POST_NOTIFICATIONS permission")
                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
            }
        }
    }
}
