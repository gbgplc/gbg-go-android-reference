// CameraCaptureHandler.kt
//
// A `BridgeCapabilityHandler` for the wire-level capture actions the web
// journey can request:
//
//   - "camera.document.capture" — document photo
//   - "camera.selfie.capture"   — selfie photo
//
// One handler instance per action. Each instance enforces a single
// concurrent request (a second request while one is active gets a `BUSY`
// error response), and exposes callable success / cancel / error paths
// that the host UI drives once the user finishes capture.

package com.gbg.go.reference.ui.journey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import com.gbg.gbgbridge.core.BridgeCapabilityHandler
import com.gbg.gbgbridge.core.BridgeResponder
import com.gbg.gbgbridge.models.BridgeErrorPayload
import com.gbg.gbgbridge.models.BridgeMessage
import com.gbg.gbgbridge.models.BridgeResponseStatus
import kotlinx.serialization.json.JsonElement
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
      // The SDK doesn't enforce single-flight, so the handler does it
      // here. Two concurrent capture requests for the same action would
      // race for the same camera surface and responder slot.
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
      // The user opened the system photo picker and backed out without
      // choosing an image. That dismisses the picker only — it must NOT
      // cancel the whole capture request, or the journey would receive a
      // spurious CANCELLED and the capture surface (which still offers the
      // camera and placeholder paths) would be torn down. Leave the request
      // active and return to the capture surface.
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
   * Returns a synthetic placeholder bitmap so the emulator path has
   * something to send. Useful for end-to-end bridge round-trip testing
   * without a real camera.
   */
  fun completeWithStubImage() {
    val responder = activeResponder ?: return
    success(responder, syntheticPlaceholderBitmap())
  }

  fun cancelActive(reason: String) {
    val responder = activeResponder ?: return
    cancelled(responder, reason)
  }

  // ---------------------------------------------------------------------------
  // Smart Capture (Part 2) entry points.
  //
  // These are the seam the Smart Capture SDK drives instead of the stub
  // capture surface. They build the SAME success response shape as the stub
  // paths — the bridge contract is unchanged — with the selfie path adding the
  // encrypted liveness blob the FaceCamera SDK produces. Kept deliberately
  // SDK-agnostic (plain Bitmap / ByteArray) so this file has no dependency on
  // the com.gbg.smartcapture types; SmartCaptureLauncher does the mapping.
  // ---------------------------------------------------------------------------

  /** Smart Capture document result: a (rotation-corrected) document image. */
  fun completeWithSmartCaptureImage(bitmap: Bitmap) {
    val responder = activeResponder ?: return
    success(responder, bitmap)
  }

  /**
   * Smart Capture selfie result: the preview photo plus the encrypted biometric
   * blob from the liveness check. The blob is forwarded base64-encoded under
   * `encryptedBlob` so the journey can pass it to server-side validation —
   * matching the iOS reference, whose selfie result carries the same blob.
   */
  fun completeWithSmartCaptureSelfie(previewPhoto: Bitmap, encryptedBlob: ByteArray) {
    val responder = activeResponder ?: return
    success(
      responder,
      previewPhoto,
      extraData = mapOf(
        "encryptedBlob" to JsonPrimitive(Base64.encodeToString(encryptedBlob, Base64.NO_WRAP)),
      ),
    )
  }

  /** Smart Capture failure (SDK error / no result) for the active request. */
  fun failActive(message: String) {
    val responder = activeResponder ?: return
    error(responder, "CAPTURE_FAILED", message)
  }

  private fun success(
    responder: BridgeResponder,
    bitmap: Bitmap,
    extraData: Map<String, JsonElement> = emptyMap(),
  ) {
    val base64 = bitmapToBase64Jpeg(bitmap)
    responder.respond(
      status = BridgeResponseStatus.SUCCESS,
      data = buildMap {
        put("imageBase64", JsonPrimitive(base64))
        put("imageWidth", JsonPrimitive(bitmap.width))
        put("imageHeight", JsonPrimitive(bitmap.height))
        put("mimeType", JsonPrimitive("image/jpeg"))
        putAll(extraData)
      },
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
