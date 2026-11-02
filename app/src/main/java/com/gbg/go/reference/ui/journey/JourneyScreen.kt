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
// Capture requests are handled by registering `BridgeCapabilityHandler`s
// against the wire-level action ids `camera.document.capture` and
// `camera.selfie.capture`.
//
// PART 2 adds passport chip reading through the SDK's typed `nfcRead` slot
// (`document.nfc` / `document.nfc.read`). Unlike the camera handlers above,
// the SDK owns that protocol: the app assigns a handler that runs the Ozone
// reader and returns `nfcRead.awaitCompletion()`, and the reader's result is
// fed back with `nfcRead.complete(...)`.
//
// Lifecycle:
//   - BridgeHost is held in a `remember { }` tied to the JourneyScreen route.
//   - DisposableEffect calls `host.detach()` on screen exit so the JS bridge
//     interface and the WebView↔host edge are torn down cleanly.
//   - The BridgeHostDelegate is held strongly by the `remember`d controller.
//     The SDK stores `BridgeHost.delegate` as a `WeakReference`, so the
//     host caller MUST keep its own strong reference or callbacks will
//     silently stop firing after the next GC.

package com.gbg.go.reference.ui.journey

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.gbg.go.reference.ui.capture.CaptureMode
import com.gbg.go.reference.ui.capture.CaptureStubScreen
import com.gbg.go.reference.ui.capture.DeviceCapabilities
import com.gbg.go.reference.ui.capture.SmartCaptureChipReader
import com.gbg.go.reference.ui.capture.SmartCaptureLauncher
import com.gbg.gbgbridge.capabilities.NfcReadError
import com.gbg.gbgbridge.capabilities.NfcReadRequest
import com.gbg.gbgbridge.capabilities.NfcReadResult
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

// Mirrors BridgeWebViewConfigurator.DEFAULT_BOOTSTRAP_SCRIPT, which is
// `internal` to the SDK and so not visible here. We need the literal because
// installing our own WebViewClient (for error logging) means we own the
// bootstrap string passed to BootstrapInjectingWebViewClient's constructor.
// Keep this in sync with the SDK default if the SDK changes it.
private const val DEFAULT_BOOTSTRAP_SCRIPT: String =
  "window.GBGBridge = window.GBGBridge || {}; " +
    "window.GBGBridge.receive = window.GBGBridge.receive || function(){};"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyScreen(
  journeyUrl: String,
  onBack: () -> Unit,
  onJourneyEnded: (status: JourneyTerminalStatus, instanceId: String?, interactionId: String?) -> Unit,
) {
  val context = LocalContext.current

  // PART 2 — Smart Capture vs stub decision, made once for this screen.
  // On real devices we hand capture off to the Smart Capture SDKs; on
  // emulators / no-camera devices we keep the stub surface so the journey
  // still runs end-to-end (the Android analogue of iOS's simulator fallback).
  val useSmartCapture = remember { DeviceCapabilities.supportsSmartCapture(context) }

  // The BridgeHost lives for the lifetime of this screen. Compose's
  // `remember` keeps it across recompositions but releases on navigation
  // away — DisposableEffect below detaches the WebView edge first.
  // The application context, not the Activity: the controller outlives
  // recompositions and must not pin the Activity.
  val appContext = context.applicationContext
  val controller = remember(journeyUrl) {
    BridgeController(
      hostVersion = HOST_VERSION,
      canReadChip = { DeviceCapabilities.supportsChipReading(appContext) },
    )
  }

  // Drive recomposition when the host raises an error. lastError is mutated
  // by the SDK on the main thread; we mirror it into Compose state via
  // the delegate's onError + onMessage hooks, which run after every
  // SDK-internal write.
  var hostErrorBanner by remember { mutableStateOf<String?>(null) }

  // Single-flight: only one capture handler can be active at a time
  // (mirrors the BUSY guard inside CameraCaptureHandler). The handler's
  // onActivate/onDeactivate callbacks below flip this between modes.
  var activeCapture by remember { mutableStateOf<CaptureMode?>(null) }

  // Modern system photo picker. PickVisualMedia replaces the legacy
  // GetContent chooser with a privacy-friendlier surface and pre-filtered
  // image-only results.
  val pickDocumentImage = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    controller.documentHandler.onPickerResult(context, uri)
  }

  val pickSelfieImage = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    controller.selfieHandler.onPickerResult(context, uri)
  }

  // PART 2 — Smart Capture activity launchers. The SDKs present full-screen
  // Activities (not Compose overlays), so we launch them via the standard
  // StartActivityForResult contract and route the result back through the
  // same handler the stub path uses. Only created/used when useSmartCapture.
  val launchSmartCaptureDocument = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    SmartCaptureLauncher.handleDocumentResult(result, controller.documentHandler)
  }

  val launchSmartCaptureSelfie = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    SmartCaptureLauncher.handleSelfieResult(result, controller.selfieHandler)
  }

  // PART 2 — passport chip reading. The request in flight, or null when idle.
  // Set when the SDK's nfcRead slot calls our handler; cleared when the reader
  // returns. The Ozone reader is a full-screen Activity like the cameras, so
  // it is launched the same way. Its result resumes the slot via complete().
  var activeChipRead by remember { mutableStateOf<NfcReadRequest?>(null) }
  val launchChipReader = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    activeChipRead = null
    controller.host.nfcRead.complete(SmartCaptureChipReader.toResult(result))
  }

  // Wire the handlers' "I need a UI" callbacks back to Compose state.
  // The terminal-event hook navigates forward; once it fires the host is torn
  // down by the route change, so guard against re-entry with a one-shot flag.
  var terminalFired by remember { mutableStateOf(false) }
  LaunchedEffect(controller) {
    controller.documentHandler.onActivate = { activeCapture = CaptureMode.Document }
    controller.documentHandler.onDeactivate = {
      if (activeCapture == CaptureMode.Document) activeCapture = null
    }
    controller.selfieHandler.onActivate = { activeCapture = CaptureMode.Selfie }
    controller.selfieHandler.onDeactivate = {
      if (activeCapture == CaptureMode.Selfie) activeCapture = null
    }
    controller.onChipReadRequested = { request -> activeChipRead = request }
    controller.delegate.onErrorBanner = { hostErrorBanner = it }
    controller.delegate.onTerminalEvent = { status, instanceId, interactionId ->
      if (!terminalFired) {
        terminalFired = true
        onJourneyEnded(status, instanceId, interactionId)
      }
    }
  }

  // PART 2 — when a capture becomes active and this device uses Smart Capture,
  // launch the corresponding SDK Activity. Keyed on `activeCapture` so it fires
  // exactly once per activation (and is a no-op when it resets to null after
  // the handler completes). On stub devices this does nothing and the
  // CaptureStubScreen below is shown instead.
  LaunchedEffect(activeCapture, useSmartCapture) {
    if (!useSmartCapture) return@LaunchedEffect
    when (activeCapture) {
      CaptureMode.Document -> launchSmartCaptureDocument.launch(
        SmartCaptureLauncher.documentIntent(context),
      )
      CaptureMode.Selfie -> launchSmartCaptureSelfie.launch(
        SmartCaptureLauncher.selfieIntent(context),
      )
      null -> Unit
    }
  }

  // PART 2 — when a chip read becomes active, open the Ozone reader. A device
  // with no NFC reader never gets here: the SDK answers UNSUPPORTED from
  // `deviceCanRead` first. NFC switched off is different, and the SDK has a
  // code for it, so answer that without opening the reader.
  LaunchedEffect(activeChipRead) {
    val request = activeChipRead ?: return@LaunchedEffect
    if (!DeviceCapabilities.isNfcEnabled(context)) {
      activeChipRead = null
      controller.host.nfcRead.complete(
        NfcReadResult.Failed(NfcReadError.NFC_DISABLED, "NFC is turned off in system settings"),
      )
    } else {
      launchChipReader.launch(SmartCaptureChipReader.intent(context, request))
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

            // On debuggable builds, expose this WebView to chrome://inspect
            // so a blank/broken journey can be inspected with full DevTools.
            // Gated on FLAG_DEBUGGABLE so release APKs never opt in.
            val debuggable = (ctx.applicationInfo.flags and
              android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
              WebView.setWebContentsDebuggingEnabled(true)
            }

            // attach() configures the WebView internally (it calls
            // BridgeWebViewConfigurator.configure with the host's bootstrap
            // and the client passed here), so we hand our custom WebViewClient
            // straight to attach() rather than to a standalone configure()
            // call — a configure() before attach() would just be re-run and
            // clobbered by attach()'s own configure(client = null).
            //
            // DiagnosticWebViewClient subclasses the SDK's
            // BootstrapInjectingWebViewClient: it keeps the bridge bootstrap
            // (via super.onPageStarted) and adds load/HTTP/SSL error logging.
            //
            // The WebChromeClient (console logging) is NOT installed by
            // attach(), and must be set AFTER it — configure() installs a
            // plain WebChromeClient, so setting ours earlier would be
            // overwritten.
            controller.host.attach(
              webView = this,
              client = DiagnosticWebViewClient(DEFAULT_BOOTSTRAP_SCRIPT),
            )
            webChromeClient = DiagnosticWebChromeClient()
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

  // Stub capture surface — shown only when this device falls back from Smart
  // Capture (emulator / no camera). On real devices the SDK Activity launched
  // by the effect above owns the capture UI instead.
  if (!useSmartCapture) {
    activeCapture?.let { mode ->
      val activeHandler = when (mode) {
        CaptureMode.Document -> controller.documentHandler
        CaptureMode.Selfie -> controller.selfieHandler
      }
      val activePicker = when (mode) {
        CaptureMode.Document -> pickDocumentImage
        CaptureMode.Selfie -> pickSelfieImage
      }
      CaptureStubScreen(
        mode = mode,
        onCapture = { bitmap -> activeHandler.onCameraResult(bitmap) },
        onPickFromLibrary = {
          activePicker.launch(
            PickVisualMediaRequest(
              ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
          )
        },
        onUseStubImage = { activeHandler.completeWithStubImage() },
        onCancel = { activeHandler.cancelActive("User dismissed capture") },
      )
    }
  }

  DisposableEffect(controller) {
    onDispose {
      // Cancel any in-flight handler so the JS side doesn't hang waiting on
      // a response that will never arrive (rotation, navigation away, etc.).
      // Wrap the cancel calls so a transient throw on the way out doesn't
      // skip detach().
      try {
        controller.documentHandler.cancelActive("Journey screen disposed")
        controller.selfieHandler.cancelActive("Journey screen disposed")
      } finally {
        // detach() also cancels a chip read in flight (the SDK owns that slot).
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
 * composable: BridgeHost.delegate is stored as a WeakReference. If we
 * constructed the delegate inline at host.delegate = SomeImpl(), it would
 * be eligible for GC the moment composition finished and callbacks would
 * silently stop firing. Holding it as a property of a `remember`d
 * controller keeps it pinned until the screen leaves composition.
 */
private class BridgeController(
  hostVersion: String,
  /** Whether this device has an NFC reader. Read on every `capability.query`. */
  canReadChip: () -> Boolean,
) {

  val delegate = LoggingDelegate()

  val documentHandler = CameraCaptureHandler(action = "camera.document.capture")
  val selfieHandler = CameraCaptureHandler(action = "camera.selfie.capture")

  /**
   * PART 2 — set by JourneyScreen. Called on the main thread when the web
   * journey requests a chip read; the screen opens the reader and later calls
   * `host.nfcRead.complete(...)` with what it read.
   */
  var onChipReadRequested: ((NfcReadRequest) -> Unit)? = null

  // The static capability map advertised on `capability.query`. Both camera
  // slots declare `supported = true` so the web journey knows it can request
  // these actions; the actual native UI presentation happens inside the
  // handlers above. `document.nfc` is NOT listed here: the SDK adds it itself
  // from the nfcRead slot below, with `supported` reflecting deviceCanRead.
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

    // PART 2 — chip reading through the SDK's typed slot. Assigning a handler
    // enables the capability; deviceCanRead decides whether it is advertised as
    // supported. The handler hands the request to the screen and suspends until
    // the reader's result arrives via complete(). The SDK validates the request,
    // answers BUSY while a read is running, and builds the wire response.
    host.nfcRead.deviceCanRead = canReadChip
    host.nfcRead.handler = { request ->
      val requested = onChipReadRequested
      if (requested == null) {
        // No screen wired up yet. Not reachable once the journey is on screen.
        NfcReadResult.Failed(NfcReadError.NFC_READ_FAILED, "No screen to read the chip from")
      } else {
        requested(request)
        host.nfcRead.awaitCompletion()
      }
    }
  }
}

/**
 * BridgeHostDelegate that lifts SDK errors into a Compose-observable
 * `onErrorBanner` callback for a thin red banner at the bottom of the
 * journey view.
 *
 * `onMessage` and `onMessageSent` hooks are kept around as a dev-loop
 * trace channel — wired to logcat so you can see every bridge envelope
 * round-trip while debugging. They don't affect UI.
 */
private class LoggingDelegate : BridgeHostDelegate {
  var onErrorBanner: ((String?) -> Unit)? = null

  // The web journey emits `journey.completed` / `journey.failed` /
  // `journey.abandoned` as one-way bridge EVENTs (not REQUESTs) when the
  // journey reaches a terminal state. The event carries `instanceId` and
  // `interactionId` in its data map. Because events don't go through the
  // request/response handler dispatch path, the only place to observe
  // them on the host side is `onMessage`.
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

/**
 * WebViewClient that preserves the SDK's bridge bootstrap injection (by
 * calling `super.onPageStarted`) and adds logging for the failure modes that
 * otherwise present as a silent blank page:
 *
 *   - `onReceivedError`     — network/DNS/connection failures (e.g. the
 *     emulator's DNS-over-TLS hanging so a hostname never resolves).
 *   - `onReceivedHttpError` — the server responded, but with 4xx/5xx.
 *   - `onReceivedSslError`  — TLS validation failed. We still `cancel()` (the
 *     secure default); we only add a log line so the cause is visible.
 *
 * Errors for sub-frames / sub-resources are logged at a lower level so the
 * main-frame navigation failure (the one that blanks the screen) stands out.
 */
private class DiagnosticWebViewClient(
  bootstrapScript: String,
) : BridgeWebViewConfigurator.BootstrapInjectingWebViewClient(bootstrapScript) {

  override fun onReceivedError(
    view: WebView,
    request: android.webkit.WebResourceRequest,
    error: android.webkit.WebResourceError,
  ) {
    super.onReceivedError(view, request, error)
    val msg = "load error ${error.errorCode} (${error.description}) for ${request.url}"
    if (request.isForMainFrame) Log.e(TAG, "WebView $msg") else Log.d(TAG, "WebView sub-resource $msg")
  }

  override fun onReceivedHttpError(
    view: WebView,
    request: android.webkit.WebResourceRequest,
    errorResponse: android.webkit.WebResourceResponse,
  ) {
    super.onReceivedHttpError(view, request, errorResponse)
    val msg = "HTTP ${errorResponse.statusCode} (${errorResponse.reasonPhrase}) for ${request.url}"
    if (request.isForMainFrame) Log.e(TAG, "WebView $msg") else Log.d(TAG, "WebView sub-resource $msg")
  }

  override fun onReceivedSslError(
    view: WebView,
    handler: android.webkit.SslErrorHandler,
    error: android.net.http.SslError,
  ) {
    // Log, then preserve the secure default (cancel). Do NOT call
    // handler.proceed() — that would defeat TLS validation.
    Log.e(TAG, "WebView SSL error ${error.primaryError} for ${error.url}")
    handler.cancel()
  }
}

/**
 * WebChromeClient that forwards the page's `console.*` output to logcat, so
 * JavaScript errors thrown inside the journey SPA are visible during a dev
 * loop instead of vanishing into a blank WebView.
 */
private class DiagnosticWebChromeClient : android.webkit.WebChromeClient() {
  override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
    val line = "WebView console [${message.messageLevel()}] " +
      "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
    when (message.messageLevel()) {
      android.webkit.ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, line)
      android.webkit.ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, line)
      else -> Log.d(TAG, line)
    }
    return true
  }
}

internal fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 85): String {
  val output = ByteArrayOutputStream()
  bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
  return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

internal fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? =
  context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
