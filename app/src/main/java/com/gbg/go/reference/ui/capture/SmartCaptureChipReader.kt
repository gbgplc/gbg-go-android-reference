// SmartCaptureChipReader.kt
//
// PART 2 — passport chip reading with Smart Capture Ozone.
//
// The only file that touches the com.gbg.smartcapture.ozone types. GBGBridge
// 1.1.0 added a typed slot for chip reads, `BridgeHost.nfcRead`, that owns the
// protocol: it validates the request, answers BUSY and UNSUPPORTED itself, and
// shapes the response the web journey's NFC card expects. The app only has to
// run a reader and hand back what it read as an `NfcReadResult`.
//
// Ozone's `OzoneUiActivity` draws the whole chip-read experience (where to
// hold the phone, progress, retry) and reads the chip. This file maps the
// bridge request onto its launch Intent, and its ActivityResult back onto an
// `NfcReadResult`. Same pattern as SmartCaptureLauncher for the cameras.
//
// The access key (document number, date of birth, date of expiry) is the
// holder's personal data. Never log it.

package com.gbg.go.reference.ui.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.gbg.gbgbridge.capabilities.NfcAuthCheck
import com.gbg.gbgbridge.capabilities.NfcAuthResult
import com.gbg.gbgbridge.capabilities.NfcAuthType
import com.gbg.gbgbridge.capabilities.NfcReadError
import com.gbg.gbgbridge.capabilities.NfcReadFile
import com.gbg.gbgbridge.capabilities.NfcReadRequest
import com.gbg.gbgbridge.capabilities.NfcReadResult
import com.gbg.smartcapture.commons.SmartCaptureException
import com.gbg.smartcapture.ozone.model.OzoneInputData
import com.gbg.smartcapture.ozone.model.OzoneResultData
import com.gbg.smartcapture.ozoneui.OzoneUiActivity

internal object SmartCaptureChipReader {

  /** Intent that launches the Ozone chip reader with [request]'s access key. */
  fun intent(context: Context, request: NfcReadRequest): Intent =
    Intent(context, OzoneUiActivity::class.java).putExtra(
      OzoneUiActivity.INPUT_DATA,
      OzoneInputData(
        request.documentNumber,
        request.dateOfBirth,
        request.dateOfExpiry,
        // Screenshots stay off: the reader shows the holder's details.
        false,
        // The issuing country is optional for Ozone and not part of the request.
        null,
      ),
    )

  /** Map the reader's ActivityResult onto the result the bridge slot expects. */
  fun toResult(result: ActivityResult): NfcReadResult = when (result.resultCode) {
    Activity.RESULT_OK ->
      OzoneUiActivity.getReadNfcData()?.let(::toResult)
        ?: NfcReadResult.Failed(NfcReadError.NFC_READ_FAILED, "Chip read returned no result")
    Activity.RESULT_CANCELED -> NfcReadResult.Cancelled("User closed the chip reader")
    OzoneUiActivity.RESULT_FAIL -> {
      @Suppress("DEPRECATION") // The typed getSerializableExtra overload is API 33; minSdk is 24.
      val error = result.data
        ?.getSerializableExtra(OzoneUiActivity.RESULT_FAIL_EXTRA_ERROR) as? SmartCaptureException
      NfcReadResult.Failed(
        NfcReadError.NFC_READ_FAILED,
        error?.let { "${it.errorCode}: ${it.message}" } ?: "Chip read failed",
      )
    }
    else -> NfcReadResult.Failed(
      NfcReadError.NFC_READ_FAILED,
      "Chip read ended with code ${result.resultCode}",
    )
  }

  /**
   * Every data group the chip returned, plus the outcome of each security
   * check. The SDK does the rest: it drops files outside the contract, decides
   * from the BAC / PACE results whether the chip actually opened, and reads the
   * MRZ out of DG1.
   */
  private fun toResult(data: OzoneResultData): NfcReadResult = NfcReadResult.Read(
    files = OzoneResultData.ByteGroup.entries.mapNotNull { group ->
      data.getBytes(group)?.let { NfcReadFile(group.name, it) }
    },
    checks = listOf(
      NfcAuthCheck(NfcAuthType.BASIC_ACCESS_CONTROL, auth(data.BACStatus)),
      NfcAuthCheck(NfcAuthType.SUPPLEMENTAL_ACCESS_CONTROL, auth(data.PACEStatus)),
      NfcAuthCheck(NfcAuthType.CHIP_AUTHENTICATION, auth(data.chipAuthenticationStatus)),
      NfcAuthCheck(NfcAuthType.ACTIVE_AUTHENTICATION, auth(data.activeAuthenticationStatus)),
      // Ozone's check of the data groups against the chip's signed hash list (SOD).
      NfcAuthCheck(
        NfcAuthType.PASSIVE_AUTHENTICATION,
        if (data.passportDataValid) NfcAuthResult.SUCCESS else NfcAuthResult.FAILURE,
      ),
    ),
  )

  /** A missing status means the protocol was not attempted. */
  private fun auth(status: OzoneResultData.AuthStatus?): NfcAuthResult = when (status) {
    null, OzoneResultData.AuthStatus.Skipped -> NfcAuthResult.SKIPPED
    OzoneResultData.AuthStatus.Success -> NfcAuthResult.SUCCESS
    OzoneResultData.AuthStatus.Failure -> NfcAuthResult.FAILURE
  }
}
