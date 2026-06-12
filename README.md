# GBG Go Android Reference — Part 2: Smart Capture SDK Integration

> **Branch:** `part-2-smart-capture` — builds on the [Part 1 reference](../../tree/main)
> (`main` branch), which covers core bridge integration with the stub capture
> surface.

A complete, runnable reference integration of the GBG Go identity
verification journey in an Android app using the **GBGBridge Android SDK**,
**with the real Smart Capture SDKs** for document scanning and face capture
with liveness detection.

This project demonstrates:

- Everything from Part 1 (journey URL, `BridgeWebView`, the bridge protocol,
  the stub capture surface)
- **Replacing the stub capture surface with the Smart Capture SDKs** —
  guided document scanning with auto-crop and quality scoring
  (`com.gbg.smartcapture:documentcamera`), and face capture with liveness
  detection plus an encrypted biometric blob (`com.gbg.smartcapture:facecamera`)
- A runtime fallback to the stub surface on emulators / no-camera devices, so
  the journey still runs end-to-end without real hardware

## What changed from Part 1

The bridge architecture was designed so swapping the stub surface for real
SDKs is a small, localised change — the handler registration, the
`BridgeResponder` contract, and the wire format are all unchanged:

| File | Change |
|------|--------|
| `app/src/main/java/com/gbg/go/reference/ui/capture/SmartCaptureLauncher.kt` | New — maps the Smart Capture Activities onto `CameraCaptureHandler` |
| `app/src/main/java/com/gbg/go/reference/ui/capture/DeviceCapabilities.kt` | New — decides Smart Capture vs stub fallback at runtime |
| `app/src/main/java/com/gbg/go/reference/ui/journey/CameraCaptureHandler.kt` | Modified — Smart Capture success paths (selfie forwards the encrypted blob) |
| `app/src/main/java/com/gbg/go/reference/ui/journey/JourneyScreen.kt` | Modified — launches the SDK on real devices, falls back to the stub surface otherwise |
| `app/build.gradle.kts` + `settings.gradle.kts` | Modified — Smart Capture dependencies + their S3 Maven repo |

Everything else — the companion server, `JourneyService`, the bridge wiring,
`CaptureStubScreen` — is identical to Part 1.

## Prerequisites

- **Android Studio Hedgehog (2023.1.1) or newer**, JDK 17 bundled
- **Android SDK** with platform 34 installed (`compileSdk = 34`)
- **An Android device or emulator running API 24+** (Android 7.0 Nougat or
  newer; `minSdk = 24`)
- **Node.js 18+** for the companion server
- **GBG Go account** with API credentials (client ID / secret, username /
  password)
- **Smart Capture SDK access** — the AWS access key / secret for the
  Smart Capture Maven repository, obtained from your GBG account
  representative. Building this branch requires them (see
  [Enabling Smart Capture](#enabling-smart-capture)); the
  [Part 1 branch](../../tree/main) needs none of this.

## Quick start

### 1. Clone and check out Part 2

```bash
git clone https://github.com/gbgplc/gbg-go-android-reference.git
cd gbg-go-android-reference
git checkout part-2-smart-capture
```

### 2. Add the SDKs

The reference app depends on `com.gbg:gbgbridge-sdk:0.1.0-alpha01`, which is
published to [Maven Central](https://central.sonatype.com/artifact/com.gbg/gbgbridge-sdk)
and resolves automatically.

This branch additionally depends on the Smart Capture SDKs
(`com.gbg.smartcapture:{commons,documentcamera,facecamera}`), which are served
from a credentialed S3 Maven repository — **the build will not resolve them
until you add your Smart Capture credentials**. See
[Enabling Smart Capture](#enabling-smart-capture) below.

### 3. Set up the companion server

The Android reference ships its own companion server under `server/`.

```bash
cd <repo>/server
npm install
cp .env.example .env
# Edit .env with your GBG Go API credentials
node index.mjs
# Server starts at http://localhost:3000
```

See [`server/README.md`](server/README.md) for the full endpoint reference
and environment-variable list.

### 4. Open and run

```bash
cd <path>/gbg-go-android-reference
./gradlew assembleDebug
```

Or open the project in Android Studio (`File → Open → gbg-go-android-reference`)
and press **Run**.

The app launches on the **Setup** screen. From the **Android emulator** the
companion server's `localhost:3000` is reachable as
`http://10.0.2.2:3000` — the default in the URL field. On a real device
on the same Wi-Fi, replace it with your machine's LAN IP.

Capture behaviour depends on the hardware: on a **real device** the journey's
document and selfie requests open the Smart Capture SDK screens; on an
**emulator** (no usable camera) it automatically falls back to the stub
capture surface.

## Project structure

```
gbg-go-android-reference/
├── server/                         # Node.js companion server (Express)
│   ├── index.mjs                   # POST /api/journey/start
│   ├── .env.example
│   ├── package.json
│   └── README.md
│
├── app/
│   ├── build.gradle.kts            # Resolves com.gbg:gbgbridge-sdk
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/gbg/go/reference/
│       │   ├── MainActivity.kt           # Single-Activity host + nav graph
│       │   ├── ui/setup/SetupScreen.kt   # Server URL + resource id form
│       │   ├── ui/journey/
│       │   │   ├── JourneyScreen.kt      # WebView host + bridge wiring
│       │   │   ├── JourneyService.kt     # Companion-server HTTP client
│       │   │   └── CameraCaptureHandler.kt  # camera.* capability handler
│       │   ├── ui/capture/
│       │   │   ├── CaptureStubScreen.kt    # Stub surface (emulator fallback)
│       │   │   ├── SmartCaptureLauncher.kt # Smart Capture SDK ↔ handler glue
│       │   │   └── DeviceCapabilities.kt   # Smart Capture vs stub decision
│       │   └── ui/theme/Theme.kt
│       └── res/
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/network_security_config.xml  # Loopback HTTP allow
├── settings.gradle.kts
├── build.gradle.kts
└── gradle/wrapper/                 # Gradle 8.13
```

## How it works

```
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│  Android App │──1──▸ │  Companion   │──2──▸ │ GBG Go Core  │
│              │       │   Server     │       │    SDK       │
│              │◂──4── │              │◂──3── │              │
└──────┬───────┘       └──────────────┘       └──────────────┘
       │
       │ 5. Load journey URL in BridgeWebView (WebView + BridgeHost)
       ▼
┌──────────────┐
│  Web Journey │──6──▸ Requests native capabilities (camera, etc.)
│  (WebView)   │◂──7── Receives capture results from native handlers
└──────────────┘
```

1. The Android app calls the companion server to start a journey
2. The server uses `@gbg/go-core` to create a session
3. The Core SDK returns an instance ID and journey URL
4. The server returns the URL to the Android app
5. The app loads the URL inside a `WebView` configured by
   `BridgeWebViewConfigurator` and attached via `BridgeHost.attach(...)`
6. The web journey requests native capabilities via `window.GBGBridge.postMessage(...)`
7. The app's registered `BridgeCapabilityHandler`s respond via
   `BridgeResponder.respond(status, data, error)`

## Bridge integration cheat-sheet

If you copy this app's wiring into your own host, the load-bearing pieces
are:

1. **Construct the host with a static capability map** so `capability.query`
   responses include accurate `supported`/`version` for each capability.
2. **Assign the delegate via a strongly-held property**. The SDK stores
   `BridgeHost.delegate` as a `WeakReference`. Inline
   `host.delegate = SomeImpl()` will work for one cycle and then silently
   stop firing callbacks once GC runs.
3. **Call `BridgeWebViewConfigurator.configure(webView)` BEFORE setting any
   custom `WebViewClient`/`WebChromeClient`**. The configurator replaces
   both because the API-26+ getters aren't available at minSdk 24.
4. **Detach on screen exit** (`DisposableEffect.onDispose` for Compose,
   `onDestroy` for an Activity). The SDK's weak references break the
   retention chain, but explicit detach is what releases the JS interface.
5. **Cancel in-flight handlers** before detach so the JS side doesn't
   hang on a response that never arrives.

## Enabling Smart Capture

The Smart Capture SDKs are distributed from a **credentialed S3 Maven
repository**, not Maven Central — so unlike `gbgbridge-sdk` they are not
committed to or resolvable from this repo without access.

### 1. Obtain credentials

Contact your GBG account representative for the Smart Capture Maven access
key and secret.

### 2. Add the credentials (never commit them)

Put them in your **user-global** Gradle properties — `~/.gradle/gradle.properties`,
which lives outside any repository and so can never be committed:

```properties
awsAccessKey=YOUR_ACCESS_KEY_ID
awsSecretKey=YOUR_SECRET_ACCESS_KEY
```

CI environments can instead export them as environment variables:

```bash
export ORG_GRADLE_PROJECT_awsAccessKey=YOUR_ACCESS_KEY_ID
export ORG_GRADLE_PROJECT_awsSecretKey=YOUR_SECRET_ACCESS_KEY
```

`settings.gradle.kts` reads these and registers the S3 repository only when
both are present. Do **not** put them in the repo's `gradle.properties`,
`local.properties`, or `settings.gradle.kts` — this is a public reference
repository.

### 3. Build and run on a device

```bash
./gradlew :app:assembleDebug
```

Smart Capture needs real camera hardware, so build and run on a **physical
device**. On an emulator the app falls back to the stub capture surface even
with credentials configured.

## Without Smart Capture

If you don't have Smart Capture access, build the [Part 1 reference](../../tree/main)
on `main` instead — it behaves identically up to the capture surface and needs
no extra credentials. (Checking out this branch without credentials fails at
Gradle dependency resolution, before anything compiles.)

## Branch structure

| Branch | Content |
|--------|---------|
| [`main`](../../tree/main) | Part 1 — core bridge integration with the stub capture surface |
| `part-2-smart-capture` | Part 2 — Smart Capture SDK integration (this branch) |

To see exactly what Part 2 adds, diff the branches:

```bash
git diff main..part-2-smart-capture
```

## License

MIT — see [LICENSE](LICENSE).
