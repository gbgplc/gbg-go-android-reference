// CameraCaptureHandler.kt
//
// A `BridgeCapabilityHandler` for the wire-level capture actions the iOS
// reference's typed slots map to:
//
//   - "camera.document.capture" — document photo
//   - "camera.selfie.capture"   — selfie photo
//
// Mirrors the slot lifecycle in spirit — single concurrent request guarded
// by a `BUSY` response, callable cancel/error paths from the UI. PARITY.md
// flags typed slots as deferred on Android; this handler is the manual
// wiring that the iOS BridgeHost auto-generates from `host.documentCapture`.

package com.gbg.go.reference.ui.journey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.gbg.gbgbridge.core.BridgeCapabilityHandler
import com.gbg.gbgbridge.core.BridgeResponder
import com.gbg.gbgbridge.models.BridgeErrorPayload
import com.gbg.gbgbridge.models.BridgeMessage
import com.gbg.gbgbridge.models.BridgeResponseStatus
import kotlinx.serialization.json.JsonPrimitive

internal class CameraCaptureHandler(
  override val action: String,
) : BridgeCapabilityHandler {

  private var activeResponder: BridgeResponder? = null

  /** Set by JourneyScreen — invoked on the main thread when a request arrives. */
  var onActivate: (() -> Unit)? = null

  /** Set by JourneyScreen — invoked when the request is resolved (success / cancel / error). */
  var onDeactivate: (() -> Unit)? = null

  override fun handle(request: BridgeMessage, responder: BridgeResponder) {
    if (activeResponder != null) {
      // PARITY.md doesn't enforce single-flight at the SDK level — the iOS
      // typed slot does this and we mirror the behaviour for parity.
      responder.respond(
        status = BridgeResponseStatus.ERROR,
        error = BridgeErrorPayload(
          code = "BUSY",
          message = "A $action request is already active",
          recoverable = true,
        ),
      )
      return
    }

    activeResponder = responder
    onActivate?.invoke()
  }

  fun onCameraResult(bitmap: Bitmap?) {
    val responder = activeResponder ?: return
    if (bitmap == null) {
      cancelled(responder, "User cancelled camera capture")
      return
    }
    success(responder, bitmap)
  }

  fun onPickerResult(context: Context, uri: Uri?) {
    val responder = activeResponder ?: return
    if (uri == null) {
      cancelled(responder, "User cancelled image selection")
      return
    }
    val bitmap = try {
      decodeBitmap(context, uri)
    } catch (error: Throwable) {
      error(responder, "PICKER_FAILED", "Failed to read selected image: ${error.message}")
      return
    }
    if (bitmap == null) {
      error(responder, "DECODE_FAILED", "Unable to decode selected image")
      return
    }
    success(responder, bitmap)
  }

  /**
   * Returns a synthetic placeholder bitmap so the simulator/emulator path
   * has something to send. Mirrors iOS's `SimulatorSelfieCaptureView`
   * fallback — useful for end-to-end bridge round-trip testing without a
   * real camera.
   */
  fun completeWithStubImage() {
    val responder = activeResponder ?: return
    success(responder, syntheticPlaceholderBitmap())
  }

  fun cancelActive(reason: String) {
    val responder = activeResponder ?: return
    cancelled(responder, reason)
  }

  private fun success(responder: BridgeResponder, bitmap: Bitmap) {
    val base64 = bitmapToBase64Jpeg(bitmap)
    responder.respond(
      status = BridgeResponseStatus.SUCCESS,
      data = mapOf(
        "imageBase64" to JsonPrimitive(base64),
        "imageWidth" to JsonPrimitive(bitmap.width),
        "imageHeight" to JsonPrimitive(bitmap.height),
        "mimeType" to JsonPrimitive("image/jpeg"),
      ),
    )
    finish()
  }

  private fun cancelled(responder: BridgeResponder, message: String) {
    responder.respond(
      status = BridgeResponseStatus.CANCELLED,
      error = BridgeErrorPayload(
        code = "CANCELLED",
        message = message,
        recoverable = true,
      ),
    )
    finish()
  }

  private fun error(responder: BridgeResponder, code: String, message: String) {
    responder.respond(
      status = BridgeResponseStatus.ERROR,
      error = BridgeErrorPayload(
        code = code,
        message = message,
        recoverable = true,
      ),
    )
    finish()
  }

  private fun finish() {
    activeResponder = null
    onDeactivate?.invoke()
  }

  private fun syntheticPlaceholderBitmap(): Bitmap {
    val width = 320
    val height = 480
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(220, 220, 230))
    val paint = Paint().apply {
      color = Color.DKGRAY
      textSize = 28f
      isAntiAlias = true
    }
    canvas.drawText("STUB CAPTURE", 60f, 220f, paint)
    canvas.drawText(action, 60f, 260f, paint)
    return bitmap
  }
}
