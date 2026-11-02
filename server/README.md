# Companion Server

A minimal Express server that creates GBG Go journey sessions using the
Core SDK (`@gbg/go-core`). The Android reference app calls this server to
obtain a journey URL.

## Prerequisites

- **Node.js 18+**
- **GBG Go API credentials** — obtain from your GBG account representative
  or the GBG Go portal

## Setup

```bash
npm install
cp .env.example .env
```

Edit `.env` with your credentials:

| Variable | Description |
|----------|-------------|
| `GO_CLIENT_ID` | Your GBG Go client ID |
| `GO_CLIENT_SECRET` | Your GBG Go client secret |
| `GO_USERNAME` | API username (email) |
| `GO_PASSWORD` | API password |
| `GO_SERVER_IDX` | Regional server: `0` = EU, `1` = US, `2` = AU |
| `GO_RESOURCE_ID` | Default resource ID for journeys (overridable per-request) |
| `GO_SERVER_URL` | Optional. Captain API base URL override (nonprod / custom host) |
| `GO_AUTH_URL` | Optional. Token endpoint host override. Defaults to the prod auth service, or to the nonprod one when `GO_SERVER_URL` is set |
| `PORT` | Server port (default: `3000`) |

## Running

```bash
node index.mjs
```

The server authenticates with GBG Go on startup and listens for requests.

## Endpoints

### `GET /`

Returns server status:

```json
{
  "service": "gbg-go-android-reference-server",
  "status": "running",
  "region": "EU",
  "authenticated": true
}
```

### `GET /health`

Health check:

```json
{ "ok": true }
```

### `POST /api/journey/start`

Creates a new journey session and returns the URL for the Android app to load.

**Request body** (all fields optional):

```json
{
  "resourceId": "a4c68509c24789888eb466@latest"
}
```

**Response:**

```json
{
  "instanceId": "PiIuACmx8Q8R7qPnAkLAqBAT",
  "journeyUrl": "https://...",
  "connectToken": "s4FUx8Ny8ijXRtFigz3x1_8rb9bd_5ZD",
  "expiresIn": 120
}
```

The `journeyUrl` is what the Android app loads in its `WebView`. The
`connectToken` is short-lived (typically 120 seconds).

## Connecting from the Android emulator

The emulator routes `localhost` to itself, not to the host machine. To
reach the server from the emulator, use the canonical loopback alias
`http://10.0.2.2:3000` — that's the default the **Setup** screen ships
with.

On a physical device on the same Wi-Fi, replace it with the host
machine's LAN IP (e.g. `http://192.168.1.100:3000`). You may also need
to flip `android:usesCleartextTraffic` and/or the
`network_security_config.xml` allowlist for non-loopback hosts.
