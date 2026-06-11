/**
 * GBG Go Android Reference — Companion Server
 *
 * A minimal Express server that demonstrates how to create journey sessions
 * using the GBG Go Core SDK (@gbg/go-core). The Android reference app calls
 * this server to obtain a journey URL, which it then loads in a WebView
 * configured by the GBGBridge Android SDK.
 *
 * Flow:
 *   1. On startup the server authenticates with GBG Go using your credentials.
 *   2. POST /api/journey/start creates a new journey, registers a mobile
 *      device, connects it, and returns the journey URL plus tokens.
 *   3. The Android app receives the URL and loads it in its WebView.
 *
 * This is functionally identical to the iOS reference's companion server —
 * only the device naming differs ("Android Reference App") so the device
 * audit trail on the GBG side reflects which host produced the session.
 *
 * Usage:
 *   cp .env.example .env   # Fill in your GBG Go credentials
 *   npm install
 *   node index.mjs
 */

import "dotenv/config";
import express from "express";
import { Go } from "@gbg/go-core";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const PORT = process.env.PORT || 3000;
const SERVER_IDX = parseInt(process.env.GO_SERVER_IDX || "0", 10);
const REGIONS = ["EU", "US", "AU"];
const SERVER_URL = process.env.GO_SERVER_URL || undefined;

// Server selection, shared by auth and every Captain call so they can never
// target different environments.
//
//   - No GO_SERVER_URL  → prod: select the regional server by index. The SDK
//     maps SERVER_IDX to the correct per-operation URL (the token endpoint and
//     the Captain endpoints have separate server lists), so this is the single
//     region knob for prod.
//   - GO_SERVER_URL set → a custom/nonprod host (e.g. the demo fabric env).
//     Used verbatim for Captain calls. The token endpoint lives at the host
//     ROOT, not under /v2/captain, so we strip that suffix to derive the auth
//     base — mirroring the two separate server lists the SDK ships.
const CAPTAIN_OPTS = SERVER_URL
  ? { serverURL: SERVER_URL }
  : { serverIdx: SERVER_IDX };
const AUTH_OPTS = SERVER_URL
  ? { serverURL: SERVER_URL.replace(/\/v2\/captain\/?$/, "") }
  : { serverIdx: SERVER_IDX };

if (!SERVER_URL && (SERVER_IDX < 0 || SERVER_IDX >= REGIONS.length)) {
  console.warn(
    `GO_SERVER_IDX=${process.env.GO_SERVER_IDX} is out of range ` +
      `(expected 0–${REGIONS.length - 1}); SDK calls will fail until corrected.`
  );
}

const app = express();
app.use(express.json());

// ---------------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------------
//
// GBG Go issues short-lived access tokens (the `expiresIn` below is in
// seconds — typically only a few minutes) and the password grant returns NO
// refresh token. So "renewal" is simply re-running the same password auth
// with the stored credentials.
//
// We authenticate lazily: `getValidToken()` re-authenticates on demand when
// the cached token is missing or within REFRESH_SKEW_MS of expiry, so a
// long-running server keeps working instead of silently 401-ing until it's
// restarted. A startup call still runs as a fail-fast smoke check.

// Re-authenticate this many ms before the token's stated expiry, so an
// in-flight request never races the boundary and lands on a just-expired
// token.
const REFRESH_SKEW_MS = 30_000;
// Fallback lifetime if the auth response omits `expiresIn` (optional in the
// SDK schema). Deliberately short so we re-auth often rather than trust a
// stale token.
const FALLBACK_TTL_MS = 60_000;

let customerAccessToken = null;
let tokenExpiresAt = 0; // epoch ms; 0 = no valid token cached
let authInFlight = null; // shared promise so concurrent callers auth once

async function authenticate() {
  const go = new Go();
  const auth = await go.tokens.generate(
    {
      clientId: process.env.GO_CLIENT_ID,
      clientSecret: process.env.GO_CLIENT_SECRET,
      username: process.env.GO_USERNAME,
      password: process.env.GO_PASSWORD,
      grantType: "password",
    },
    AUTH_OPTS
  );
  // `accessToken` is optional in the SDK's response schema. A 200 with no
  // token would otherwise be cached as `undefined` (then re-auth on every
  // request and surface downstream as an opaque 500) — fail loudly instead.
  if (!auth.accessToken) {
    throw new Error("Authentication succeeded but no accessToken was returned");
  }
  customerAccessToken = auth.accessToken;
  const ttlMs =
    typeof auth.expiresIn === "number" ? auth.expiresIn * 1000 : FALLBACK_TTL_MS;
  tokenExpiresAt = Date.now() + ttlMs;
  if (typeof auth.expiresIn !== "number") {
    console.warn(
      `Auth response omitted expiresIn; assuming ${FALLBACK_TTL_MS / 1000}s lifetime`
    );
  }
  // Report the actual target, not just the index — when GO_SERVER_URL is set
  // it (not the region index) is what's really in effect.
  const target = SERVER_URL
    ? SERVER_URL
    : `${REGIONS[SERVER_IDX] ?? "?"} region (serverIdx ${SERVER_IDX})`;
  console.log(
    `Authenticated with GBG Go [${target}], ` +
      `token valid for ${Math.round(ttlMs / 1000)}s`
  );
}

/**
 * Return a non-expired customer access token, re-authenticating if the cached
 * one is missing or about to expire. Concurrent callers share a single
 * in-flight auth so a burst of requests doesn't trigger a burst of logins.
 * Throws if authentication fails (e.g. bad credentials).
 */
async function getValidToken() {
  if (customerAccessToken && Date.now() < tokenExpiresAt - REFRESH_SKEW_MS) {
    return customerAccessToken;
  }
  if (!authInFlight) {
    authInFlight = authenticate().finally(() => {
      authInFlight = null;
    });
  }
  await authInFlight;
  return customerAccessToken;
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

/** GET / — server status */
app.get("/", (_req, res) => {
  res.json({
    service: "gbg-go-android-reference-server",
    status: "running",
    region: REGIONS[SERVER_IDX],
    authenticated: customerAccessToken !== null,
  });
});

/** GET /health — health check */
app.get("/health", (_req, res) => {
  res.json({ ok: true });
});

/**
 * POST /api/journey/start — create a new journey session
 *
 * Request body (all fields optional):
 *   {
 *     "resourceId": "a4c68509c24789888eb466@latest"
 *   }
 *
 * Response:
 *   {
 *     "instanceId": "PiIuACmx8Q8R7qPnAkLAqBAT",
 *     "journeyUrl": "https://...",
 *     "connectToken": "s4FUx8Ny...",
 *     "expiresIn": 120
 *   }
 */
app.post("/api/journey/start", async (req, res) => {
  try {
    let token;
    try {
      token = await getValidToken();
    } catch (authErr) {
      return res.status(503).json({
        error: "Server not authenticated. Check your .env credentials.",
        message: authErr.message,
      });
    }

    const serverOpts = CAPTAIN_OPTS;
    const client = new Go({ customerAccess: token });

    // Step 1: Start a journey session
    const journey = await client.journeys.start(
      {
        resourceId: req.body.resourceId ?? process.env.GO_RESOURCE_ID,
        context: {
          subject: {},
          config: { delivery: "page" },
        },
      },
      serverOpts
    );
    console.log("Journey started:", journey.instanceId);
    // Step 2: Register a mobile device against the journey
    const device = await client.devices.add(
      {
        instanceId: journey.instanceId,
        scope: ["mobile"],
      },
      serverOpts
    );
    console.log("Device registered:", device);
    // Step 3: Connect the device to obtain an end-user token
    const connection = await client.devices.connect(
      { deviceConnect: device.connectToken },
      {
        deviceInfo: {
          deviceName: "Android Reference App",
          deviceId: "android-reference-device",
          deviceType: "mobile",
        },
        connectToken: device.connectToken,
      },
      serverOpts
    );
    console.log("Device connected:", connection);
    // Step 4: Fetch the interaction to validate the session
    const unauthenticatedClient = new Go();
    let interactions;
    try {
      interactions = await unauthenticatedClient.interactions.fetch(
        { interactionAccess: String(connection.endUserToken) },
        { instanceId: journey.instanceId },
        serverOpts
      );
    } catch (err) {
      console.error(err);
      console.error("Failed to fetch interaction:", err.message);
      interactions = { error: err.message };
    }

    // `interactions.fetch` returns a discriminated union — either a populated
    // ResponseBody (`instanceId` / `interactionId` / `journey` / `interaction`)
    // or a FetchInteractionError (`status` / `code` / `message`). It's not an
    // array, so don't expect `.length`. Log a shape-aware summary instead.
    if ("interactionId" in interactions) {
      console.log(
        "Interaction fetched:",
        interactions.interactionId,
        "for journey",
        journey.instanceId
      );
    } else {
      console.log(
        "Interaction fetch returned error variant for journey",
        journey.instanceId,
        "—",
        interactions
      );
    }

    // Return the journey details to the Android app
    const result = {
      instanceId: journey.instanceId,
      journeyUrl: journey.instanceUrl,
      connectToken: device.connectToken,
      expiresIn: device.expiresIn,
    };
    console.log("Journey started:", result);
    res.json(result);
  } catch (err) {
    // The SDK's typed errors carry diagnostic state the bare `err.message`
    // doesn't surface. ResponseValidationError in particular has a `.body`
    // (raw response that failed zod validation) and `.cause` (the underlying
    // zod issue). Print both so the operator can see exactly which field /
    // value the SDK rejected — without this the symptom is a flat "response
    // validation failed" with nothing to chase.
    const cause = err?.cause?.message;
    const rawBody = err?.body;
    const httpStatus = err?.statusCode ?? err?.rawResponse?.status;
    console.error(
      "Journey start failed:",
      err?.constructor?.name ?? "Error",
      "—",
      err.message
    );
    if (httpStatus) {
      console.error("  HTTP status:", httpStatus);
    }
    if (cause) {
      console.error("  Cause:", cause);
    }
    if (rawBody) {
      try {
        const parsed = JSON.parse(rawBody);
        console.error("  Response body:", JSON.stringify(parsed, null, 2));
      } catch {
        console.error("  Response body (raw):", rawBody);
      }
    }
    res.status(500).json({
      error: "Failed to start journey",
      message: err.message,
      detail: cause ?? undefined,
    });
  }
});

/**
 * GET /api/journey/:instanceId/state — fetch the authoritative outcome
 *
 * The Android app calls this from its Result screen after the web journey
 * has signalled a terminal state over the bridge. The bridge event tells the
 * app *that* the journey ended; this endpoint returns *what* the outcome was
 * (verdict, collected data, decision details) by calling the GBG Go Core
 * SDK's `journeys.getState`.
 *
 * Note for integrators: in production the canonical pattern is to receive
 * Captain webhooks on your backend and push updates to the app via your own
 * channel (push notifications, server-sent events, GraphQL subscriptions),
 * not to have the app poll. The endpoint below is wired this way to keep the
 * reference build simple and self-contained.
 */
app.get("/api/journey/:instanceId/state", async (req, res) => {
  try {
    let token;
    try {
      token = await getValidToken();
    } catch (authErr) {
      return res.status(503).json({
        error: "Server not authenticated. Check your .env credentials.",
        message: authErr.message,
      });
    }

    const serverOpts = CAPTAIN_OPTS;
    const client = new Go({ customerAccess: token });

    const state = await client.journeys.getState(
      { instanceId: req.params.instanceId },
      serverOpts,
    );
    res.json(state);
  } catch (err) {
    const cause = err?.cause?.message;
    const httpStatus = err?.statusCode ?? err?.rawResponse?.status;
    console.error(
      "getState failed:",
      err?.constructor?.name ?? "Error",
      "—",
      err.message,
    );
    if (httpStatus) console.error("  HTTP status:", httpStatus);
    if (cause) console.error("  Cause:", cause);
    res.status(500).json({
      error: "Failed to fetch journey state",
      message: err.message,
      detail: cause ?? undefined,
    });
  }
});

// ---------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------

async function start() {
  // Fail-fast smoke check. Authentication is lazy (see getValidToken), so a
  // transient failure here isn't fatal — routes will retry on demand — but a
  // misconfiguration (bad credentials) is worth surfacing loudly at startup.
  try {
    await authenticate();
  } catch (err) {
    console.error("Startup authentication failed:", err.message);
    console.error(
      "The server will still start and retry auth on the first request, " +
        "but if this is a credentials problem those requests will return 503."
    );
    console.error("Check your .env credentials.");
  }

  app.listen(PORT, () => {
    console.log(`Server listening on http://localhost:${PORT}`);
    console.log(`  GET  /                    — status`);
    console.log(`  GET  /health              — health check`);
    console.log(`  POST /api/journey/start             — create journey session`);
    console.log(`  GET  /api/journey/:instanceId/state — fetch journey outcome`);
  });
}

start();
