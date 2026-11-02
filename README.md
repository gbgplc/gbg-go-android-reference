# GBG Go Android Reference

A complete, runnable reference integration of the GBG Go identity
verification journey in an Android app using the **GBGBridge Android SDK**.

This project demonstrates:

- Calling a companion server to obtain a journey URL
- Hosting the journey inside a `WebView` via `BridgeWebViewConfigurator` +
  `BridgeHost.attach(...)`
- Implementing `BridgeCapabilityHandler`s for `camera.document.capture`
  and `camera.selfie.capture` with system-camera + photo-library fallback
- Returning `success` / `cancelled` / `error` results back to the journey
  via `BridgeResponder.respond(...)`

## Prerequisites

- **Android Studio Hedgehog (2023.1.1) or newer**, JDK 17 bundled
- **Android SDK** with platform 34 installed (`compileSdk = 34`)
- **An Android device or emulator running API 24+** (Android 7.0 Nougat or
  newer; `minSdk = 24`)
- **Node.js 18+** for the companion server
- **GBG Go account** with API credentials (client ID / secret, username /
  password)

## Quick start

### 1. Add the GBGBridge SDK

The reference app depends on `com.gbg:gbgbridge-sdk:1.1.0`, which is
published to [Maven Central](https://central.sonatype.com/artifact/com.gbg/gbgbridge-sdk).
The build resolves it automatically — no extra setup required.

### 2. Set up the companion server

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

### 3. Open and run

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
│       │   ├── ui/capture/CaptureStubScreen.kt  # Full-screen capture surface
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

## License

MIT — see [LICENSE](LICENSE).
