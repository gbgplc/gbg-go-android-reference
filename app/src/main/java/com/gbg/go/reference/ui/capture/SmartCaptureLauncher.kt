// SmartCaptureLauncher.kt
//
// PART 2 — Smart Capture SDK integration.
//
// The only file that touches the com.gbg.smartcapture SDK types. It maps the
// SDK's Activity-based capture flow onto the existing CameraCaptureHandler:
//
//   - Document: DocumentCameraActivity (guided scan, auto-crop, quality
//     scoring). Result is read from DocumentCameraActivity.latestResult after
//     RESULT_OK: the cropped document as JPEG bytes plus a preview bitmap.
//   - Selfie:   FaceCameraActivity (face capture + passive liveness). Result
//     comes from FaceCameraActivity.getResult(); errors arrive as RESULT_ERROR
//     with a SmartCaptureException extra.
//
// Both SDKs are launched via the standard StartActivityForResult contract.
// Results are funnelled back through CameraCaptureHandler's Smart Capture entry
// points, so the bridge wiring in JourneyScreen is identical to the stub path —
// only the UI that produces the image changes (the iOS "swap point").
//
// Unlike the stub CaptureStubScreen (a Compose Dialog), Smart Capture runs as a
// full-screen Activity, so there is no composable to render here: JourneyScreen
// launches the Activity when a capture becomes active and this file maps the
// returned result.

package com.gbg.go.reference.ui.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.result.ActivityResult
import com.gbg.go.reference.ui.journey.CameraCaptureHandler
import com.gbg.smartcapture.commons.SmartCaptureException
import com.gbg.smartcapture.documentcamera.DocumentCameraActivity
import com.gbg.smartcapture.documentcamera.DocumentProcessingState
import com.gbg.smartcapture.documentcamera.DocumentScannerConfig
import com.gbg.smartcapture.facecamera.FaceCameraActivity

internal object SmartCaptureLauncher {

  /** Intent that launches the Smart Capture document scanner. */
  fun documentIntent(context: Context): Intent =
    DocumentCameraActivity.getIntent(context, DocumentScannerConfig())

  /** Intent that launches the Smart Capture face/liveness camera. */
  fun selfieIntent(context: Context): Intent =
    Intent(context, FaceCameraActivity::class.java)

  /**
   * Map a document-scanner ActivityResult onto [handler]. Smart Capture 1.4.x
   * returns the cropped, rotation-corrected document as JPEG bytes (plus a
   * smaller preview bitmap). The full image is decoded and handed over; the
   * handler re-encodes it into the standard bridge response.
   */
  fun handleDocumentResult(result: ActivityResult, handler: CameraCaptureHandler) {
    when (result.resultCode) {
      Activity.RESULT_OK -> when (val state = DocumentCameraActivity.latestResult) {
        is DocumentProcessingState.Success -> {
          val image = BitmapFactory.decodeByteArray(state.bytes, 0, state.bytes.size)
            ?: state.previewBitmap
          handler.completeWithSmartCaptureImage(image)
        }
        is DocumentProcessingState.Failure ->
          handler.failActive(state.message ?: "Document capture failed")
        else ->
          handler.failActive("Document capture returned no result")
      }
      Activity.RESULT_CANCELED -> handler.cancelActive("User cancelled document capture")
      else -> handler.failActive("Document capture error (code ${result.resultCode})")
    }
  }

  /**
   * Map a face-camera ActivityResult onto [handler]. On success the preview
   * photo and the encrypted liveness blob are forwarded together.
   */
  fun handleSelfieResult(result: ActivityResult, handler: CameraCaptureHandler) {
    when (result.resultCode) {
      Activity.RESULT_OK -> {
        val faceResult = FaceCameraActivity.getResult()
        if (faceResult != null) {
          handler.completeWithSmartCaptureSelfie(
            previewPhoto = faceResult.previewPhoto,
            encryptedBlob = faceResult.encryptedBlob,
          )
        } else {
          handler.failActive("Face capture returned no result")
        }
      }
      Activity.RESULT_CANCELED -> handler.cancelActive("User cancelled face capture")
      FaceCameraActivity.RESULT_ERROR -> {
        @Suppress("DEPRECATION")
        val exception =
          result.data?.getSerializableExtra(FaceCameraActivity.ERROR_OBJECT) as? SmartCaptureException
        handler.failActive(exception?.message ?: "Face capture failed")
      }
      else -> handler.failActive("Face capture error (code ${result.resultCode})")
    }
  }
}
