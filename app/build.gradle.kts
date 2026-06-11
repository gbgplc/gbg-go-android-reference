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
  compileSdk = 34

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
  implementation("com.gbg:gbgbridge-sdk:0.1.0-alpha01")

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
  val cameraXVersion = "1.3.4"
  implementation("androidx.camera:camera-core:$cameraXVersion")
  implementation("androidx.camera:camera-camera2:$cameraXVersion")
  implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
  implementation("androidx.camera:camera-view:$cameraXVersion")
}
