// SetupScreen.kt
//
// The first screen the user sees: two inputs (companion server URL +
// optional resource id) and a Start Journey button. On tap, calls
// JourneyService.startJourney → navigates to JourneyScreen with the
// returned URL.
//
// Persistence: SharedPreferences holds the last-used values across
// launches. The server URL defaults to `http://10.0.2.2:3000` — the
// Android emulator's loopback alias for a server running on the host
// machine. (The emulator routes `localhost` / `127.0.0.1` to the emulator
// itself, not the host, so the alias is required for reaching a local
// companion server.)

package com.gbg.go.reference.ui.setup

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gbg.go.reference.ui.journey.JourneyService
import kotlinx.coroutines.launch

private const val PREFS_NAME = "gbg_go_reference_prefs"
private const val KEY_SERVER_URL = "serverURL"
private const val KEY_RESOURCE_ID = "resourceId"

// `localhost` from inside the Android emulator points at the emulator
// itself, not the host machine. `10.0.2.2` is the canonical loopback alias
// for the host.
private const val DEFAULT_SERVER_URL = "http://10.0.2.2:3000"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onJourneyStarted: (journeyUrl: String, serverUrl: String) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

  var serverUrl by remember {
    mutableStateOf(prefs.getString(KEY_SERVER_URL, null) ?: DEFAULT_SERVER_URL)
  }
  var resourceId by remember {
    mutableStateOf(prefs.getString(KEY_RESOURCE_ID, null) ?: "")
  }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // Persist on change so the user doesn't have to re-enter between launches.
  LaunchedEffect(serverUrl, resourceId) {
    prefs.edit()
      .putString(KEY_SERVER_URL, serverUrl)
      .putString(KEY_RESOURCE_ID, resourceId)
      .apply()
  }

  val scope = rememberCoroutineScope()

  Scaffold(
    topBar = {
      TopAppBar(title = { Text("GBG Go Reference") })
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "Configure the companion server connection and journey " +
          "template, then tap Start Journey to begin.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      HorizontalDivider()

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Companion Server",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
          value = serverUrl,
          onValueChange = { serverUrl = it },
          label = { Text("Server URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Text(
          text = "The companion server creates journey sessions using the " +
            "GBG Go Core SDK. See server/README.md for setup. From the " +
            "Android emulator use 10.0.2.2 to reach a server on the host.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      HorizontalDivider()

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Journey Template",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
          value = resourceId,
          onValueChange = { resourceId = it },
          label = { Text("Resource ID (optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          text = "Leave blank to use the server's default. Format: " +
            "<id>@<version> (e.g. abc123@latest).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      errorMessage?.let { message ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          HorizontalDivider()
          Spacer(Modifier.height(4.dp))
          Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start,
          ) {
            Icon(
              imageVector = Icons.Filled.Warning,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              text = message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      Button(
        onClick = {
          isLoading = true
          errorMessage = null
          scope.launch {
            try {
              val trimmedServerUrl = serverUrl.trim()
              val response = JourneyService.startJourney(
                serverUrl = trimmedServerUrl,
                resourceId = resourceId.trim().takeIf { it.isNotEmpty() },
              )
              onJourneyStarted(response.journeyUrl, trimmedServerUrl)
            } catch (error: Throwable) {
              errorMessage = error.message ?: error::class.java.simpleName
            } finally {
              isLoading = false
            }
          }
        },
        enabled = !isLoading && serverUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp,
            modifier = Modifier.height(20.dp),
          )
        } else {
          Text(
            text = "Start Journey",
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}
