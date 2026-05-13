// JourneyService.kt
//
// Calls the companion server to create a journey session. The server handles
// authentication with GBG Go and returns a journey URL for the app to load.
//
// This is the Android side of the flow documented in the Journey URL guide:
//
//   Android App  ──▸  Your Backend  ──▸  GBG Go Core SDK
//                ◂──                ◂──
//
// In production, "your backend" is your own server. Here we use the companion
// server included in the iOS reference repository (gbg-go-ios-reference/server)
// — both reference apps share it.

package com.gbg.go.reference.ui.journey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object JourneyService {

  @Serializable
  data class StartResponse(
    val journeyUrl: String,
    val instanceId: String,
    val connectToken: String? = null,
    val expiresIn: Int? = null,
  )

  class JourneyServiceException(
    message: String,
    cause: Throwable? = null,
  ) : RuntimeException(message, cause)

  private val json = Json { ignoreUnknownKeys = true }
  private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  /**
   * Fetches the authoritative journey outcome from the companion server,
   * which proxies to GBG Go's `journeys.getState`. Returned as a pretty-
   * printed JSON string so the Result screen can display it verbatim — the
   * response shape is large and customer-specific, so we deliberately don't
   * model it as a typed Kotlin class here.
   */
  suspend fun fetchState(serverUrl: String, instanceId: String): String =
    withContext(Dispatchers.IO) {
      val base = serverUrl.trimEnd('/')
      if (base.isEmpty() || (!base.startsWith("http://") && !base.startsWith("https://"))) {
        throw JourneyServiceException(
          "Invalid server URL: '$serverUrl'. Use a full http(s):// URL.",
        )
      }
      if (instanceId.isBlank()) {
        throw JourneyServiceException("Missing instanceId — nothing to fetch.")
      }

      val endpoint = "$base/api/journey/$instanceId/state"
      val request = Request.Builder().url(endpoint).get().build()

      val response = try {
        client.newCall(request).execute()
      } catch (error: IOException) {
        throw JourneyServiceException(
          buildConnectionFailureMessage(endpoint, error),
          error,
        )
      }

      response.use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          val message = parseErrorMessage(raw) ?: "HTTP ${resp.code}"
          throw JourneyServiceException("Server returned ${resp.code}: $message")
        }
        // Re-encode through the configured Json so the output is pretty-
        // printed regardless of how the server formatted it.
        try {
          val element = json.parseToJsonElement(raw)
          prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
        } catch (error: Throwable) {
          // Fall back to the raw body if it isn't valid JSON for any reason.
          raw
        }
      }
    }

  suspend fun startJourney(serverUrl: String, resourceId: String?): StartResponse =
    withContext(Dispatchers.IO) {
      val base = serverUrl.trimEnd('/')
      if (base.isEmpty() || (!base.startsWith("http://") && !base.startsWith("https://"))) {
        throw JourneyServiceException(
          "Invalid server URL: '$serverUrl'. Use a full http(s):// URL " +
            "(e.g. http://10.0.2.2:3000).",
        )
      }

      val endpoint = "$base/api/journey/start"

      val bodyPayload: Map<String, JsonPrimitive> =
        if (resourceId.isNullOrEmpty()) emptyMap()
        else mapOf("resourceId" to JsonPrimitive(resourceId))

      val bodyJson = json.encodeToString(JsonObject(bodyPayload))
      val body = bodyJson.toRequestBody("application/json".toMediaType())

      val request = Request.Builder()
        .url(endpoint)
        .post(body)
        .header("Content-Type", "application/json")
        .build()

      val response = try {
        client.newCall(request).execute()
      } catch (error: IOException) {
        throw JourneyServiceException(
          buildConnectionFailureMessage(endpoint, error),
          error,
        )
      }

      response.use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          val message = parseErrorMessage(raw) ?: "HTTP ${resp.code}"
          throw JourneyServiceException("Server returned ${resp.code}: $message")
        }
        try {
          json.decodeFromString(StartResponse.serializer(), raw)
        } catch (error: Throwable) {
          throw JourneyServiceException(
            "Unexpected server response: ${error.message ?: error::class.java.simpleName}",
            error,
          )
        }
      }
    }

  /**
   * Build a user-facing message for a failed connect. If the URL targets a
   * loopback host (`localhost` / `127.0.0.1` / `::1`), append the canonical
   * Android-emulator workaround — from inside the emulator the loopback
   * address points at the emulator itself, not the developer's Mac, so a
   * server running on the host needs the `10.0.2.2` alias instead.
   */
  private fun buildConnectionFailureMessage(endpoint: String, error: IOException): String {
    val base = "Cannot reach the server at $endpoint: ${error.message}. Is it running?"
    val host = runCatching { java.net.URI(endpoint).host?.lowercase() }.getOrNull()
    val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    if (!isLoopback) return base
    return base + "\n\nFrom inside the Android emulator, `localhost` / `127.0.0.1` " +
      "routes to the emulator itself — not your Mac. To reach a companion " +
      "server running on the host, change the Server URL to " +
      "`http://10.0.2.2:3000` (the emulator's loopback alias for the host), " +
      "or run `adb reverse tcp:3000 tcp:3000` from your Mac to forward the " +
      "port and keep using 127.0.0.1."
  }

  private fun parseErrorMessage(rawBody: String): String? {
    if (rawBody.isBlank()) return null
    return try {
      val element = json.parseToJsonElement(rawBody)
      val obj = element as? JsonObject ?: return rawBody.take(200)
      (obj["message"] ?: obj["error"])?.toString()?.trim('"')
    } catch (_: Throwable) {
      rawBody.take(200)
    }
  }
}
