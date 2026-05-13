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

const app = express();
app.use(express.json());

// ---------------------------------------------------------------------------
// Authentication — generate an access token on startup
// ---------------------------------------------------------------------------

let customerAccessToken = null;

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
    {
      serverURL: "https://gbggo4-demo-au.nonprod.fabric.gbgplatforms.com",
    }
  );
  customerAccessToken = auth.accessToken;
  console.log(`Authenticated with GBG Go (${REGIONS[SERVER_IDX]} region)`);
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
    if (!customerAccessToken) {
      return res.status(503).json({
        error: "Server not authenticated. Check your .env credentials.",
      });
    }

    const serverOpts = SERVER_URL ? { serverURL: SERVER_URL } : {};
    const client = new Go({ customerAccess: customerAccessToken });

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
    if (!customerAccessToken) {
      return res.status(503).json({
        error: "Server not authenticated. Check your .env credentials.",
      });
    }

    const serverOpts = SERVER_URL ? { serverURL: SERVER_URL } : {};
    const client = new Go({ customerAccess: customerAccessToken });

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
    console.log("Device registered:", device.deviceId);
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
    console.log("Journey started:", result.instanceId);
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

// ---------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------

async function start() {
  try {
    await authenticate();
  } catch (err) {
    console.error("Authentication failed:", err.message);
    console.error(
      "The server will start, but /api/journey/start will return 503."
    );
    console.error("Check your .env credentials and try restarting.");
  }

  app.listen(PORT, () => {
    console.log(`Server listening on http://localhost:${PORT}`);
    console.log(`  GET  /                              — status`);
    console.log(`  GET  /health                        — health check`);
    console.log(`  POST /api/journey/start             — create journey session`);
    console.log(`  GET  /api/journey/:instanceId/state — fetch journey outcome`);
  });
}

start();
