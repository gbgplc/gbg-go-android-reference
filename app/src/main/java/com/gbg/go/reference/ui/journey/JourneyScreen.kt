// JourneyScreen.kt
//
// This is the core integration screen — the most important file in the
// reference app. It demonstrates the complete pattern for hosting a GBG Go
// identity journey on Android:
//
//   1. Construct a BridgeHost with the app's capability map
//   2. Register handlers for the capabilities the journey can request
//   3. Configure a WebView via BridgeWebViewConfigurator and host.attach()
//   4. Load the journey URL
//   5. Present native UI when a capture request arrives, return the result
//      back to the journey via BridgeResponder.respond(...)
//
// The iOS reference mirrors this with `host.documentCapture` / `host.selfieCapture`
// typed slots; those are deferred on Android (see PARITY.md), so the same
// behaviour is achieved here with plain `BridgeCapabilityHandler`s registered
// against `camera.document.capture` and `camera.selfie.capture` actions —
// the wire-level action ids the iOS typed slots map to.
//
// Lifecycle:
//   - BridgeHost is held in a `remember { }` tied to the JourneyScreen route.
//   - DisposableEffect calls `host.detach()` on screen exit so the JS bridge
//     interface and the WebView↔host edge are torn down cleanly.
//   - The BridgeHostDelegate is held strongly by the `remember`d controller —
//     PARITY.md notes the SDK stores the delegate weakly to mirror iOS's
//     `weak var`, so the host caller MUST keep its own strong reference.

package com.gbg.go.reference.ui.journey

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gbg.go.reference.ui.capture.SelfieStubScreen
import com.gbg.gbgbridge.core.BridgeCapabilityInfo
import com.gbg.gbgbridge.core.BridgeConfiguration
import com.gbg.gbgbridge.core.BridgeHost
import com.gbg.gbgbridge.core.BridgeHostDelegate
import com.gbg.gbgbridge.models.BridgeMessage
import com.gbg.gbgbridge.models.BridgeMessageType
import com.gbg.gbgbridge.webview.BridgeWebViewConfigurator
import com.gbg.go.reference.ui.result.JourneyTerminalStatus
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

private const val TAG = "JourneyScreen"
private const val HOST_VERSION = "1.0.0"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyScreen(
  journeyUrl: String,
  onBack: () -> Unit,
  onJourneyEnded: (status: JourneyTerminalStatus, instanceId: String?, interactionId: String?) -> Unit,
) {
  val context = LocalContext.current

  // The BridgeHost lives for the lifetime of this screen. Compose's
  // `remember` keeps it across recompositions but releases on navigation
  // away — DisposableEffect below detaches the WebView edge first.
  val controller = remember(journeyUrl) {
    BridgeController(hostVersion = HOST_VERSION).also {
      it.cameraAvailable = context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
  }

  // Drive recomposition when the host raises an error. lastError is mutated
  // by the SDK on the main thread; we mirror it into Compose state via
  // the delegate's onError + onMessage hooks, which run after every
  // SDK-internal write.
  var hostErrorBanner by remember { mutableStateOf<String?>(null) }
  var documentSourceDialog by remember { mutableStateOf(false) }
  var selfieStub by remember { mutableStateOf(false) }

  // Activity result launchers — wired to the controller's handler state so
  // the result lands on whichever handler is currently active.
  val takeDocumentPicture = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicturePreview(),
  ) { bitmap ->
    controller.documentHandler.onCameraResult(bitmap)
  }

  val pickDocumentImage = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent(),
  ) { uri ->
    controller.documentHandler.onPickerResult(context, uri)
  }

  val pickSelfieImage = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent(),
  ) { uri ->
    controller.selfieHandler.onPickerResult(context, uri)
  }

  // Wire the handlers' "I need a UI" callbacks back to Compose state.
  // The terminal-event hook navigates forward; once it fires the host is torn
  // down by the route change, so guard against re-entry with a one-shot flag.
  var terminalFired by remember { mutableStateOf(false) }
  LaunchedEffect(controller) {
    controller.documentHandler.onActivate = { documentSourceDialog = true }
    controller.documentHandler.onDeactivate = { documentSourceDialog = false }
    controller.selfieHandler.onActivate = { selfieStub = true }
    controller.selfieHandler.onDeactivate = { selfieStub = false }
    controller.delegate.onErrorBanner = { hostErrorBanner = it }
    controller.delegate.onTerminalEvent = { status, instanceId, interactionId ->
      if (!terminalFired) {
        terminalFired = true
        onJourneyEnded(status, instanceId, interactionId)
      }
    }
  }

  // Construct WebView once per journeyUrl and attach the host.
  // AndroidView's factory runs once per composition root; the update block
  // runs on each recomposition.
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Journey") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
            )
          }
        },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // BridgeWebViewConfigurator clobbers WebViewClient /
            // WebChromeClient (PARITY.md "configurator clobber"), so any
            // custom client must be set AFTER configure() / attach().
            BridgeWebViewConfigurator.configure(this)
            controller.host.attach(this)
            loadUrl(journeyUrl)
          }
        },
      )

      hostErrorBanner?.let { error ->
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
            .fillMaxWidth(),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .background(
                color = MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.small,
              )
              .padding(horizontal = 12.dp, vertical = 8.dp),
          ) {
            Text(
              text = error,
              color = Color.White,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }
  }

  if (documentSourceDialog) {
    DocumentSourceDialog(
      cameraAvailable = controller.cameraAvailable,
      onCamera = {
        documentSourceDialog = false
        takeDocumentPicture.launch(null)
      },
      onPhotoLibrary = {
        documentSourceDialog = false
        pickDocumentImage.launch("image/*")
      },
      onCancel = {
        documentSourceDialog = false
        controller.documentHandler.cancelActive("User dismissed source picker")
      },
    )
  }

  if (selfieStub) {
    SelfieStubScreen(
      onUseStubImage = {
        controller.selfieHandler.completeWithStubImage()
      },
      onPickFromLibrary = {
        pickSelfieImage.launch("image/*")
      },
      onCancel = {
        controller.selfieHandler.cancelActive("User dismissed selfie capture")
      },
    )
  }

  DisposableEffect(controller) {
    onDispose {
      // Cancel any in-flight handler so the JS side doesn't hang waiting on
      // a response that will never arrive (rotation, navigation away, etc.).
      // Wrap the cancel calls so a transient throw on the way out doesn't
      // skip detach() — mirrors the example-app's onDestroy guard.
      try {
        controller.documentHandler.cancelActive("Journey screen disposed")
        controller.selfieHandler.cancelActive("Journey screen disposed")
      } finally {
        controller.host.detach()
      }
    }
  }
}

/**
 * The strongly-held bag of host + delegate + handlers. Created once per
 * journeyUrl via `remember` and torn down by DisposableEffect.onDispose.
 *
 * Why a separate class rather than a pile of `remember { }` state in the
 * composable: PARITY.md flags that BridgeHost.delegate is stored as a
 * WeakReference (mirroring iOS's `weak var delegate`). If we constructed
 * the delegate inline at host.delegate = SomeImpl(), it would be eligible
 * for GC the moment composition finished and callbacks would silently stop
 * firing. Holding it as a property of a `remember`d controller keeps it
 * pinned until the screen leaves composition.
 */
private class BridgeController(hostVersion: String) {

  val delegate = LoggingDelegate()

  val documentHandler = CameraCaptureHandler(action = "camera.document.capture")
  val selfieHandler = CameraCaptureHandler(action = "camera.selfie.capture")

  // The static capability map advertised on `capability.query`. Both slots
  // declare `supported = true` so the web journey knows it can request
  // these actions; the actual native UI presentation happens inside the
  // handlers above.
  val host: BridgeHost = BridgeHost(
    configuration = BridgeConfiguration(
      hostVersion = hostVersion,
      capabilities = mapOf(
        "camera.document" to BridgeCapabilityInfo(supported = true, version = "1.0"),
        "camera.selfie" to BridgeCapabilityInfo(supported = true, version = "1.0"),
      ),
    ),
  ).also { host ->
    host.delegate = delegate
    host.register(documentHandler)
    host.register(selfieHandler)
  }

  var cameraAvailable: Boolean = false
}

/**
 * BridgeHostDelegate that lifts SDK errors into a Compose-observable
 * `onErrorBanner` callback. Mirrors iOS's reliance on `host.lastError` for
 * a thin red banner at the bottom of the JourneyView.
 *
 * `onMessage` and `onMessageSent` hooks are kept around as a dev-loop
 * trace channel — wired to logcat so you can see every bridge envelope
 * round-trip while debugging. They don't affect UI.
 */
private class LoggingDelegate : BridgeHostDelegate {
  var onErrorBanner: ((String?) -> Unit)? = null

  // The end-user web app emits `journey.completed` / `journey.failed` /
  // `journey.abandoned` as one-way bridge EVENTs (not REQUESTs) when the
  // journey reaches a terminal state — see useBridgeLifecycleEmitter.ts in
  // identity-gbggo-end-user-interaction. The event carries `instanceId` and
  // `interactionId` in its data map. Because events don't go through the
  // request/response handler dispatch path, the only place to observe them
  // on the host side is `onMessage`.
  var onTerminalEvent:
    ((status: JourneyTerminalStatus, instanceId: String?, interactionId: String?) -> Unit)? = null

  override fun onMessage(host: BridgeHost, message: BridgeMessage) {
    Log.d(
      TAG,
      "FROM WEB ${message.type.name.lowercase()} ${message.payload.action} ${message.correlationId}",
    )
    onErrorBanner?.invoke(host.lastError)

    if (message.type == BridgeMessageType.EVENT) {
      val terminal = when (message.payload.action) {
        "journey.completed" -> JourneyTerminalStatus.COMPLETED
        "journey.failed" -> JourneyTerminalStatus.FAILED
        "journey.abandoned" -> JourneyTerminalStatus.ABANDONED
        else -> null
      }
      if (terminal != null) {
        val data = message.payload.data
        val instanceId = data?.get("instanceId")?.jsonPrimitive?.contentOrNull
        val interactionId = data?.get("interactionId")?.jsonPrimitive?.contentOrNull
        onTerminalEvent?.invoke(terminal, instanceId, interactionId)
      }
    }
  }

  override fun onMessageSent(host: BridgeHost, message: BridgeMessage) {
    Log.d(
      TAG,
      "TO WEB   ${message.type.name.lowercase()} ${message.payload.action} ${message.correlationId}",
    )
  }

  override fun onError(host: BridgeHost, error: Throwable) {
    Log.e(TAG, "Bridge error", error)
    onErrorBanner?.invoke(host.lastError ?: error.message)
  }
}

@Composable
private fun DocumentSourceDialog(
  cameraAvailable: Boolean,
  onCamera: () -> Unit,
  onPhotoLibrary: () -> Unit,
  onCancel: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onCancel,
    title = { Text("Capture Document") },
    text = {
      Text(
        text = if (cameraAvailable) {
          "Choose a capture source. The system camera or photo library result " +
            "is returned to the journey as a base64-encoded JPEG."
        } else {
          "No camera is available on this device — only the photo library option " +
            "is enabled. Pick an existing image to return to the journey."
        },
      )
    },
    confirmButton = {
      if (cameraAvailable) {
        TextButton(onClick = onCamera) { Text("Camera") }
      } else {
        TextButton(onClick = onPhotoLibrary) { Text("Photo Library") }
      }
    },
    dismissButton = {
      Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (cameraAvailable) {
          TextButton(onClick = onPhotoLibrary) { Text("Photo Library") }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
      }
    },
  )
}

internal fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 85): String {
  val output = ByteArrayOutputStream()
  bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
  return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

internal fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? =
  context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
