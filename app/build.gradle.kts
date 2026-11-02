plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  // Required for Compose with Kotlin 2.0+ (replaces the old
  // kotlinCompilerExtensionVersion mechanism).
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "com.gbg.go.reference"
  // PART 2: 36, not 34. Smart Capture 1.4.x pulls AndroidX libraries (activity,
  // CameraX 1.5) that require compileSdk 36. targetSdk is unchanged.
  compileSdk = 36

  defaultConfig {
    applicationId = "com.gbg.go.reference"
    minSdk = 24
    targetSdk = 34
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    compose = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  // The GBGBridge SDK, published to Maven Central.
  // https://central.sonatype.com/artifact/com.gbg/gbgbridge-sdk
  implementation("com.gbg:gbgbridge-sdk:1.1.0")

  // PART 2 — Smart Capture SDKs.
  //
  // Real document scanning (guided capture, auto-crop, quality scoring), face
  // capture with liveness detection + encrypted biometric blobs, and passport
  // chip reading over NFC (Ozone). On real devices these replace the
  // CameraX/stub capture surface; on emulators (no usable camera) the app falls
  // back to CaptureStubScreen at runtime, and chip reading is reported as
  // unsupported wherever there is no NFC reader.
  //
  // Distributed from a credentialed S3 Maven repo — see settings.gradle.kts.
  // Building this branch requires Smart Capture access; the `main` branch
  // (Part 1) needs none of these.
  val smartCaptureVersion = "1.4.4"
  implementation("com.gbg.smartcapture:commons:$smartCaptureVersion")
  implementation("com.gbg.smartcapture:documentcamera:$smartCaptureVersion")
  implementation("com.gbg.smartcapture:facecamera:$smartCaptureVersion")
  // Chip reading: `ozone` reads the chip, `ozoneui` is the ready-made reader
  // screen (OzoneUiActivity) this app launches. Both need `commons`.
  implementation("com.gbg.smartcapture:ozone:$smartCaptureVersion")
  implementation("com.gbg.smartcapture:ozoneui:$smartCaptureVersion")

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

  val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation("androidx.compose.ui:ui-tooling")

  implementation("androidx.navigation:navigation-compose:2.8.1")

  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  // CameraX powers the live preview behind the framing guide in
  // CaptureStubScreen. The actual JPEG hand-off to the bridge still goes
  // through ImageCapture → Bitmap, so the bridge wiring is unchanged.
  // 1.5.3 matches the CameraX version Smart Capture 1.4.x depends on, so the
  // stub path and the SDK path share one CameraX.
  val cameraXVersion = "1.5.3"
  implementation("androidx.camera:camera-core:$cameraXVersion")
  implementation("androidx.camera:camera-camera2:$cameraXVersion")
  implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
  implementation("androidx.camera:camera-view:$cameraXVersion")
}
