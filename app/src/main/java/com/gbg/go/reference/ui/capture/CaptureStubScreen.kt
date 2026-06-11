// CaptureStubScreen.kt
//
// A unified, full-screen capture surface used for both
// `camera.document.capture` and `camera.selfie.capture` requests. Drop it
// into your own host as-is, or use it as a worked example when wiring your
// own capture UI to a BridgeCapabilityHandler.
//
// The screen offers three rungs of capture affordance, in priority order:
//
//   1. Live CameraX preview + shutter button (back camera for document,
//      front camera for selfie). This is the path your production app
//      would replace with a full Smart Capture integration.
//   2. System photo picker (`PickVisualMedia`) for choosing an existing
//      image — useful on devices without a usable camera, and the main
//      path on tablets.
//   3. Synthetic placeholder bitmap. A tertiary text button so the screen
//      still reads as a capture surface, but available so the emulator
//      path can exercise the end-to-end bridge round-trip without a real
//      camera.
//
// Permission handling: CAMERA is declared in the manifest as optional.
// We request it at runtime; if denied or the device has no camera, the
// preview area falls back to a static framing guide on a neutral
// background, and the library + placeholder options carry the user
// through.

package com.gbg.go.reference.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CaptureStubScreen"

/** Which capture flow is active — drives copy, framing guide, and camera selector. */
enum class CaptureMode { Document, Selfie }

private sealed interface CameraPhase {
  data object Initialising : CameraPhase
  data object NeedsPermission : CameraPhase
  data object PermissionDenied : CameraPhase
  data object NoCamera : CameraPhase
  data class Ready(val imageCapture: ImageCapture) : CameraPhase
  data class Failed(val reason: String) : CameraPhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureStubScreen(
  mode: CaptureMode,
  onCapture: (Bitmap) -> Unit,
  onPickFromLibrary: () -> Unit,
  onUseStubImage: () -> Unit,
  onCancel: () -> Unit,
) {
  Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
    ),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = Color.Black,
    ) {
      Scaffold(
        containerColor = Color.Transparent,
        topBar = {
          TopAppBar(
            title = {
              Text(
                text = when (mode) {
                  CaptureMode.Document -> "Capture Document"
                  CaptureMode.Selfie -> "Capture Selfie"
                },
                color = Color.White,
              )
            },
            navigationIcon = {
              IconButton(onClick = onCancel) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = "Cancel",
                  tint = Color.White,
                )
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = Color.Black.copy(alpha = 0.4f),
            ),
          )
        },
      ) { padding ->
        CaptureSurface(
          mode = mode,
          padding = padding,
          onCapture = onCapture,
          onPickFromLibrary = onPickFromLibrary,
          onUseStubImage = onUseStubImage,
        )
      }
    }
  }
}

@Composable
private fun CaptureSurface(
  mode: CaptureMode,
  padding: PaddingValues,
  onCapture: (Bitmap) -> Unit,
  onPickFromLibrary: () -> Unit,
  onUseStubImage: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var phase by remember { mutableStateOf<CameraPhase>(CameraPhase.Initialising) }
  var hasCameraFeature by remember {
    mutableStateOf(
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
    )
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    phase = if (granted) CameraPhase.Initialising else CameraPhase.PermissionDenied
  }

  // Decide initial phase based on hardware + permission state.
  LaunchedEffect(mode) {
    if (!hasCameraFeature) {
      phase = CameraPhase.NoCamera
      return@LaunchedEffect
    }
    val granted = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
      phase = CameraPhase.NeedsPermission
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  Box(modifier = Modifier.fillMaxSize().padding(padding)) {
    // Preview / fallback art fills the available space.
    Box(modifier = Modifier.fillMaxSize()) {
      // Static backdrop renders unless CameraX has fully bound; the live
      // preview's AndroidView is mounted on top once Ready.
      if (phase !is CameraPhase.Ready) {
        StaticPreviewBackdrop(mode)
      }

      // The CameraX surface (if applicable) is mounted unconditionally so
      // we can re-bind when phase becomes Ready. We hide it visually when
      // the camera isn't actually live so the static backdrop reads cleanly.
      if (hasCameraFeature) {
        CameraPreview(
          mode = mode,
          enabled = phase !is CameraPhase.PermissionDenied &&
            phase !is CameraPhase.NoCamera &&
            phase !is CameraPhase.Failed,
          context = context,
          lifecycleOwner = lifecycleOwner,
          onReady = { imageCapture -> phase = CameraPhase.Ready(imageCapture) },
          onFailure = { reason -> phase = CameraPhase.Failed(reason) },
        )
      }

      // Framing guide drawn over whatever is behind.
      FramingGuideOverlay(mode = mode)
    }

    // Bottom controls — caption + shutter + library + stub.
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .background(Color.Black.copy(alpha = 0.55f))
        .padding(horizontal = 24.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = captionForPhase(mode, phase),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Library — left of shutter.
        IconButton(
          onClick = onPickFromLibrary,
          modifier = Modifier
            .size(56.dp)
            .background(Color.White.copy(alpha = 0.15f), CircleShape),
        ) {
          Icon(
            imageVector = Icons.Filled.PhotoLibrary,
            contentDescription = "Choose from library",
            tint = Color.White,
          )
        }

        // Shutter — primary CTA when camera is live.
        ShutterButton(
          enabled = phase is CameraPhase.Ready,
          onClick = {
            val ready = phase as? CameraPhase.Ready ?: return@ShutterButton
            takePicture(
              context = context,
              imageCapture = ready.imageCapture,
              onBitmap = onCapture,
              onError = { msg ->
                Log.w(TAG, "Capture failed: $msg")
                phase = CameraPhase.Failed(msg)
              },
            )
          },
        )

        // Spacer to keep the shutter visually centred when the row has
        // only two slots (library on the left, shutter in the middle).
        Spacer(modifier = Modifier.size(56.dp))
      }

      TextButton(onClick = onUseStubImage) {
        Text(
          text = "Use placeholder image",
          color = Color.White.copy(alpha = 0.75f),
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

@Composable
private fun CameraPreview(
  mode: CaptureMode,
  enabled: Boolean,
  context: android.content.Context,
  lifecycleOwner: androidx.lifecycle.LifecycleOwner,
  onReady: (ImageCapture) -> Unit,
  onFailure: (String) -> Unit,
) {
  if (!enabled) return

  // Re-bind whenever the mode changes (front ↔ back camera selector).
  val cameraSelector = remember(mode) {
    when (mode) {
      CaptureMode.Document -> CameraSelector.DEFAULT_BACK_CAMERA
      CaptureMode.Selfie -> CameraSelector.DEFAULT_FRONT_CAMERA
    }
  }

  val previewView = remember {
    PreviewView(context).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
  }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { previewView },
  )

  LaunchedEffect(mode) {
    try {
      val provider = awaitCameraProvider(context)
      val preview = Preview.Builder().build().apply {
        setSurfaceProvider(previewView.surfaceProvider)
      }
      val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
      provider.unbindAll()
      provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
      onReady(imageCapture)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
      throw cancellation
    } catch (error: Throwable) {
      Log.w(TAG, "CameraX bind failed", error)
      onFailure(error.message ?: error::class.java.simpleName)
    }
  }

  DisposableEffect(mode) {
    onDispose {
      runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        .onFailure { Log.w(TAG, "Provider unbind on dispose failed", it) }
    }
  }
}

private suspend fun awaitCameraProvider(
  context: android.content.Context,
): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
  val future = ProcessCameraProvider.getInstance(context)
  future.addListener(
    {
      try {
        continuation.resume(future.get())
      } catch (executionError: ExecutionException) {
        continuation.resumeWithException(executionError.cause ?: executionError)
      } catch (error: Throwable) {
        continuation.resumeWithException(error)
      }
    },
    ContextCompat.getMainExecutor(context),
  )
}

private fun takePicture(
  context: android.content.Context,
  imageCapture: ImageCapture,
  onBitmap: (Bitmap) -> Unit,
  onError: (String) -> Unit,
) {
  imageCapture.takePicture(
    ContextCompat.getMainExecutor(context),
    object : ImageCapture.OnImageCapturedCallback() {
      override fun onCaptureSuccess(image: ImageProxy) {
        try {
          val raw = image.toBitmap()
          val rotation = image.imageInfo.rotationDegrees
          val rotated = if (rotation == 0) {
            raw
          } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
          }
          onBitmap(rotated)
        } catch (error: Throwable) {
          onError(error.message ?: error::class.java.simpleName)
        } finally {
          image.close()
        }
      }

      override fun onError(exception: ImageCaptureException) {
        onError(exception.message ?: "ImageCapture error ${exception.imageCaptureError}")
      }
    },
  )
}

@Composable
private fun StaticPreviewBackdrop(mode: CaptureMode) {
  // Neutral gradient stand-in for when the camera isn't available. Keeps
  // the framing guide visible against a deliberately non-camera surface.
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Color(0xFF1B1E23),
      ),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = when (mode) {
          CaptureMode.Document -> "Document Capture"
          CaptureMode.Selfie -> "Selfie Capture"
        },
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = "Camera preview is unavailable on this device or has not been " +
          "granted permission. Pick an image from the library or use the " +
          "placeholder to complete the journey.",
        color = Color.White.copy(alpha = 0.75f),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun FramingGuideOverlay(mode: CaptureMode) {
  var size by remember { mutableStateOf(IntSize.Zero) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onSizeChanged { size = it }
      .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
  ) {
    if (size != IntSize.Zero) {
      androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = Color.Black.copy(alpha = 0.55f))

        when (mode) {
          CaptureMode.Document -> {
            // ID-card aspect ratio (~1.586:1) — landscape, centred,
            // 86% of the screen width.
            val frameWidth = this.size.width * 0.86f
            val frameHeight = frameWidth / 1.586f
            val left = (this.size.width - frameWidth) / 2f
            val top = (this.size.height - frameHeight) / 2f
            val cornerRadius = CornerRadius(24f, 24f)

            drawRoundRect(
              color = Color.Transparent,
              topLeft = Offset(left, top),
              size = ComposeSize(frameWidth, frameHeight),
              cornerRadius = cornerRadius,
              blendMode = BlendMode.Clear,
            )
            drawRoundRect(
              brush = SolidColor(Color.White),
              topLeft = Offset(left, top),
              size = ComposeSize(frameWidth, frameHeight),
              cornerRadius = cornerRadius,
              style = Stroke(width = 4f),
            )
          }

          CaptureMode.Selfie -> {
            // Tall oval centred horizontally, sized to ~70% of width and
            // shifted slightly above centre to leave room for the controls.
            val frameWidth = this.size.width * 0.7f
            val frameHeight = frameWidth * 1.32f
            val left = (this.size.width - frameWidth) / 2f
            val top = (this.size.height - frameHeight) / 2f - this.size.height * 0.04f

            drawOval(
              color = Color.Transparent,
              topLeft = Offset(left, top),
              size = ComposeSize(frameWidth, frameHeight),
              blendMode = BlendMode.Clear,
            )
            drawOval(
              brush = SolidColor(Color.White),
              topLeft = Offset(left, top),
              size = ComposeSize(frameWidth, frameHeight),
              style = Stroke(width = 4f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ShutterButton(
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val outerColor = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
  val innerColor = if (enabled) Color.White else Color.White.copy(alpha = 0.2f)
  Box(
    modifier = Modifier
      .size(72.dp)
      .border(width = 4.dp, color = outerColor, shape = CircleShape)
      .padding(8.dp)
      .clip(CircleShape)
      .background(innerColor)
      .clickable(enabled = enabled, onClick = onClick),
  )
}

private fun captionForPhase(mode: CaptureMode, phase: CameraPhase): String = when (phase) {
  CameraPhase.Initialising -> "Starting camera…"
  CameraPhase.NeedsPermission -> "Camera permission is required to use the preview."
  CameraPhase.PermissionDenied ->
    "Camera permission was denied. Pick from the library or use the placeholder."
  CameraPhase.NoCamera ->
    "This device has no camera. Pick from the library or use the placeholder."
  is CameraPhase.Failed -> "Camera unavailable: ${phase.reason}"
  is CameraPhase.Ready -> when (mode) {
    CaptureMode.Document -> "Align the document inside the frame, then tap the shutter."
    CaptureMode.Selfie -> "Centre your face in the oval, then tap the shutter."
  }
}
