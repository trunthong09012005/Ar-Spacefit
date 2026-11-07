package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    isFrontCamera: Boolean = false,
    isTorchOn: Boolean = false,
    restartKey: Int = 0,
    objectDetectionEnabled: Boolean = false,
    imageCaptureCallback: ((ImageCapture?) -> Unit)? = null,
    onObjectDetected: (TrackedObject?) -> Unit = {},
    onObjectDetectionError: (String) -> Unit = {},
    onCameraReady: () -> Unit = {},
    onCameraError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView is more reliable when Compose controls are layered over the preview.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val latestImageCaptureCallback by rememberUpdatedState(imageCaptureCallback)
    val latestOnObjectDetected by rememberUpdatedState(onObjectDetected)
    val latestOnObjectDetectionError by rememberUpdatedState(onObjectDetectionError)
    val latestOnCameraReady by rememberUpdatedState(onCameraReady)
    val latestOnCameraError by rememberUpdatedState(onCameraError)
    val objectDetector = remember {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .build()
        ObjectDetection.getClient(options)
    }

    DisposableEffect(objectDetector) {
        onDispose { objectDetector.close() }
    }

    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor.shutdown() }
    }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    LaunchedEffect(isTorchOn, cameraControl) {
        try {
            cameraControl?.enableTorch(isTorchOn)
        } catch (error: Exception) {
            Log.w("CameraPreview", "Unable to change torch state", error)
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val streamObserver = Observer<PreviewView.StreamState> { streamState ->
            if (streamState == PreviewView.StreamState.STREAMING) {
                latestOnCameraReady()
            }
        }
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        onDispose {
            previewView.previewStreamState.removeObserver(streamObserver)
        }
    }

    DisposableEffect(lifecycleOwner, isFrontCamera, restartKey, previewView, objectDetectionEnabled) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var captureUseCase: ImageCapture? = null
        var analysisUseCase: ImageAnalysis? = null
        var disposed = false
        var detectionErrorReported = false

        cameraProviderFuture.addListener({
            if (!disposed) {
                try {
                    val provider = cameraProviderFuture.get()
                    val selector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    if (!provider.hasCamera(selector)) {
                        throw IllegalStateException(
                            if (isFrontCamera) "Thiết bị không có camera trước."
                            else "Thiết bị không có camera sau."
                        )
                    }

                    val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    val preview = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(targetRotation)
                        .build()
                    val analysis = if (objectDetectionEnabled) {
                        ImageAnalysis.Builder()
                            .setTargetRotation(targetRotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage == null) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }

                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val uprightWidth = if (rotation % 180 == 0) {
                                        imageProxy.width
                                    } else {
                                        imageProxy.height
                                    }
                                    val uprightHeight = if (rotation % 180 == 0) {
                                        imageProxy.height
                                    } else {
                                        imageProxy.width
                                    }
                                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
                                    objectDetector.process(inputImage)
                                        .addOnSuccessListener(mainExecutor) { objects ->
                                            detectionErrorReported = false
                                            val detected = objects.maxByOrNull { candidate ->
                                                candidate.boundingBox.width() * candidate.boundingBox.height()
                                            }
                                            if (detected == null || uprightWidth <= 0 || uprightHeight <= 0) {
                                                latestOnObjectDetected(null)
                                            } else {
                                                val bounds = detected.boundingBox
                                                latestOnObjectDetected(
                                                    TrackedObject(
                                                        left = (bounds.left.toFloat() / uprightWidth).coerceIn(0f, 1f),
                                                        top = (bounds.top.toFloat() / uprightHeight).coerceIn(0f, 1f),
                                                        right = (bounds.right.toFloat() / uprightWidth).coerceIn(0f, 1f),
                                                        bottom = (bounds.bottom.toFloat() / uprightHeight).coerceIn(0f, 1f),
                                                        sourceWidth = uprightWidth,
                                                        sourceHeight = uprightHeight,
                                                        trackingId = detected.trackingId
                                                    )
                                                )
                                            }
                                        }
                                        .addOnFailureListener(mainExecutor) { error ->
                                            latestOnObjectDetected(null)
                                            if (!detectionErrorReported) {
                                                detectionErrorReported = true
                                                latestOnObjectDetectionError(
                                                    error.localizedMessage?.takeIf { it.isNotBlank() }
                                                        ?: "Không thể khởi tạo bộ nhận diện vật thể. Hãy thử khởi động lại Camera."
                                                )
                                            }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                }
                            }
                    } else {
                        null
                    }

                    provider.unbindAll()
                    val camera = if (analysis != null) {
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)
                    } else {
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    }

                    cameraProvider = provider
                    previewUseCase = preview
                    captureUseCase = capture
                    analysisUseCase = analysis
                    cameraControl = camera.cameraControl
                    latestImageCaptureCallback?.invoke(capture)
                    if (!objectDetectionEnabled) latestOnObjectDetected(null)
                } catch (error: SecurityException) {
                    Log.e("CameraPreview", "Camera permission is missing", error)
                    latestOnCameraError("Ứng dụng chưa được cấp quyền Camera.")
                } catch (error: Exception) {
                    Log.e("CameraPreview", "Unable to bind CameraX", error)
                    latestOnCameraError(
                        error.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: "Không thể khởi động Camera trên thiết bị này."
                    )
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            latestImageCaptureCallback?.invoke(null)
            latestOnObjectDetected(null)
            cameraControl = null
            val preview = previewUseCase
            val capture = captureUseCase
            val analysis = analysisUseCase
            analysis?.clearAnalyzer()
            if (preview != null && capture != null && analysis != null) {
                cameraProvider?.unbind(preview, capture, analysis)
            } else if (preview != null && capture != null) {
                cameraProvider?.unbind(preview, capture)
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

// Capture helper to take a picture and return a rotated Bitmap
fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    executor: ExecutorService,
    onSuccess: (Bitmap) -> Unit,
    onError: (Exception) -> Unit
) {
    if (imageCapture == null) {
        onError(IllegalStateException("Camera not ready"))
        return
    }

    val photoFile = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                try {
                    val bitmap = decodeSampledBitmap(photoFile)
                        ?: throw IllegalStateException("Không thể đọc ảnh vừa chụp.")
                    ContextCompat.getMainExecutor(context).execute {
                        onSuccess(bitmap)
                    }
                } catch (e: Exception) {
                    ContextCompat.getMainExecutor(context).execute {
                        onError(e)
                    }
                } finally {
                    if (!photoFile.delete()) {
                        Log.w("CameraPreview", "Unable to delete temporary scan image")
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                ContextCompat.getMainExecutor(context).execute {
                    onError(exception)
                }
            }
        }
    )
}

fun clearTemporaryScanImages(context: Context) {
    context.cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("scan_") && file.extension.equals("jpg", ignoreCase = true)
    }?.forEach { file ->
        if (!file.delete()) Log.w("CameraPreview", "Unable to delete stale temporary scan image")
    }
}
