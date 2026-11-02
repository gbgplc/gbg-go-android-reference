// DeviceCapabilities.kt
//
// Decides whether to drive the Smart Capture SDK or fall back to the stub
// capture surface (CaptureStubScreen) at runtime.
//
// This is the Android analogue of the iOS reference's
// `#if SMART_CAPTURE_ENABLED && !targetEnvironment(simulator)` guard: the
// Smart Capture document scanner and face/liveness SDKs need real camera
// hardware and the device's native sensors, which emulators don't provide. On
// an emulator (or a device with no usable camera) we therefore keep using the
// stub surface so the journey still runs end-to-end for development and CI,
// and only hand off to Smart Capture on real devices.

package com.gbg.go.reference.ui.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gbg.gbgbridge.capabilities.NfcDetector

object DeviceCapabilities {

  /**
   * True when this build should use the Smart Capture SDKs for capture:
   * a real device (not an emulator) that actually has a camera. Otherwise the
   * caller should fall back to [CaptureStubScreen].
   */
  fun supportsSmartCapture(context: Context): Boolean =
    hasCamera(context) && !isProbablyEmulator()

  /**
   * True when this device has an NFC reader and so can read a passport chip.
   * Emulators have none. The bridge SDK cannot check this itself (it has no
   * Context), so the app feeds the answer into `BridgeHost.nfcRead.deviceCanRead`;
   * without it the SDK reports chip reading as unsupported everywhere.
   */
  fun supportsChipReading(context: Context): Boolean = NfcDetector.hasNfc(context)

  /** True when NFC is switched on in system settings (hardware present or not). */
  fun isNfcEnabled(context: Context): Boolean = NfcDetector.isNfcEnabled(context)

  private fun hasCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

  /**
   * Best-effort emulator detection from build fingerprints. Emulators report
   * `goldfish`/`ranchu` hardware, `generic` fingerprints, or `sdk_gphone`
   * product names. This deliberately errs toward the stub on anything that
   * looks emulated rather than risk a Smart Capture SDK that can't bind to a
   * real camera.
   */
  private fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val brand = Build.BRAND.lowercase()

    return fingerprint.startsWith("generic") ||
      fingerprint.startsWith("unknown") ||
      fingerprint.contains("emulator") ||
      model.contains("emulator") ||
      model.contains("android sdk built for") ||
      product.contains("sdk_gphone") ||
      product.contains("emulator") ||
      product.contains("simulator") ||
      hardware.contains("goldfish") ||
      hardware.contains("ranchu") ||
      (brand.startsWith("generic") && Build.DEVICE.lowercase().startsWith("generic"))
  }
}
