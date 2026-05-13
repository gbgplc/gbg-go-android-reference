// MainActivity.kt
//
// The single Activity that hosts the Compose navigation graph. Mirrors the
// iOS reference's `GBGGoReferenceApp` (entry point) plus `RootView`
// (NavigationStack). Two destinations:
//
//   1. SetupScreen   — server URL + resource id input → Start Journey
//   2. JourneyScreen — BridgeWebView host + capture handlers
//
// Lifecycle note: BridgeHost lives on the JourneyScreen route's lifecycle
// (constructed inside a remember { } there, attached / detached by a
// DisposableEffect). The Activity itself does not own the host — Compose
// recomposition + navigation handle that. See JourneyScreen for the wiring.

package com.gbg.go.reference

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gbg.go.reference.ui.journey.JourneyScreen
import com.gbg.go.reference.ui.result.JourneyTerminalStatus
import com.gbg.go.reference.ui.result.ResultScreen
import com.gbg.go.reference.ui.setup.SetupScreen
import com.gbg.go.reference.ui.theme.GBGGoReferenceTheme
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      GBGGoReferenceTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          RootNavGraph()
        }
      }
    }
  }
}

@Composable
private fun RootNavGraph() {
  val navController = rememberNavController()

  NavHost(navController = navController, startDestination = "setup") {
    composable("setup") {
      SetupScreen(
        onJourneyStarted = { url, serverUrl ->
          val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
          val encodedServer = URLEncoder.encode(serverUrl, StandardCharsets.UTF_8.name())
          navController.navigate("journey/$encodedServer/$encodedUrl")
        },
      )
    }

    composable("journey/{serverUrl}/{url}") { backStackEntry ->
      val args = backStackEntry.arguments
      val serverUrl = URLDecoder.decode(
        args?.getString("serverUrl").orEmpty(), StandardCharsets.UTF_8.name(),
      )
      val decoded = URLDecoder.decode(
        args?.getString("url").orEmpty(), StandardCharsets.UTF_8.name(),
      )
      JourneyScreen(
        journeyUrl = decoded,
        onBack = { navController.popBackStack() },
        onJourneyEnded = { status, instanceId, interactionId ->
          // Replace the journey on the back stack so system-back from Result
          // returns to Setup, not the now-disposed WebView.
          val sid = URLEncoder.encode(status.wireValue, StandardCharsets.UTF_8.name())
          val iid = URLEncoder.encode(instanceId.orEmpty(), StandardCharsets.UTF_8.name())
          val ixn = URLEncoder.encode(interactionId.orEmpty(), StandardCharsets.UTF_8.name())
          val srv = URLEncoder.encode(serverUrl, StandardCharsets.UTF_8.name())
          navController.navigate("result/$srv/$sid/$iid/$ixn") {
            popUpTo("journey/{serverUrl}/{url}") { inclusive = true }
          }
        },
      )
    }

    composable("result/{serverUrl}/{status}/{instanceId}/{interactionId}") { backStackEntry ->
      val args = backStackEntry.arguments
      val serverUrl = URLDecoder.decode(
        args?.getString("serverUrl").orEmpty(), StandardCharsets.UTF_8.name(),
      )
      val statusRaw = URLDecoder.decode(
        args?.getString("status").orEmpty(), StandardCharsets.UTF_8.name(),
      )
      val instanceId = URLDecoder.decode(
        args?.getString("instanceId").orEmpty(), StandardCharsets.UTF_8.name(),
      ).ifBlank { null }
      val interactionId = URLDecoder.decode(
        args?.getString("interactionId").orEmpty(), StandardCharsets.UTF_8.name(),
      ).ifBlank { null }
      ResultScreen(
        serverUrl = serverUrl,
        status = JourneyTerminalStatus.fromWire(statusRaw),
        instanceId = instanceId,
        interactionId = interactionId,
        onStartOver = {
          navController.popBackStack(route = "setup", inclusive = false)
        },
      )
    }
  }
}
