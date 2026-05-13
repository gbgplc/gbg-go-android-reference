// ResultScreen.kt
//
// Shown after a journey reaches a terminal state. JourneyScreen detects the
// terminal `journey.completed` / `journey.failed` / `journey.abandoned` event
// on the bridge (sent by the end-user web app via the lifecycle emitter) and
// navigates here, popping itself off the back stack.
//
// Two integration teaching points live here:
//
//   1. The bridge event is the immediate "journey ended" signal — that's how
//      we got to this screen at all.
//
//   2. The bridge event tells you *that* the journey ended; the Captain
//      `journeys.getState` API tells you *what* the outcome was. The
//      "Fetch outcome from Captain" button below demonstrates the second
//      step by calling a companion-server endpoint that proxies through to
//      the SDK call. In production the canonical pattern is webhook-driven
//      — your backend receives a Captain webhook on completion and pushes
//      the outcome to your app over your own channel — but the polled
//      shape below is simpler for a reference build.

package com.gbg.go.reference.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gbg.go.reference.ui.journey.JourneyService
import kotlinx.coroutines.launch

/** Terminal status reported by the web journey. */
enum class JourneyTerminalStatus(val wireValue: String, val label: String) {
  COMPLETED("completed", "Journey Completed"),
  FAILED("failed", "Journey Failed"),
  ABANDONED("abandoned", "Journey Abandoned");

  companion object {
    fun fromWire(value: String): JourneyTerminalStatus =
      values().firstOrNull { it.wireValue == value } ?: ABANDONED
  }
}

private sealed interface FetchState {
  object Idle : FetchState
  object Loading : FetchState
  data class Loaded(val prettyJson: String) : FetchState
  data class Failed(val message: String) : FetchState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
  serverUrl: String,
  status: JourneyTerminalStatus,
  instanceId: String?,
  interactionId: String?,
  onStartOver: () -> Unit,
) {
  var fetchState by remember { mutableStateOf<FetchState>(FetchState.Idle) }
  val scope = rememberCoroutineScope()

  Scaffold(
    topBar = { TopAppBar(title = { Text("Result") }) },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      StatusPill(status)

      IdRow("Instance ID", instanceId)
      IdRow("Interaction ID", interactionId)

      Text(
        text = "The web journey signalled this terminal state via a bridge " +
          "event (journey.${status.wireValue}). The bridge event is the " +
          "immediate signal that the journey ended; the button below fetches " +
          "the authoritative outcome from the Captain API via the companion " +
          "server. In production, webhook-driven backends are usually " +
          "preferable to native polling.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      FetchOutcomeSection(
        state = fetchState,
        canFetch = !instanceId.isNullOrBlank(),
        onFetch = {
          val id = instanceId ?: return@FetchOutcomeSection
          fetchState = FetchState.Loading
          scope.launch {
            fetchState = try {
              FetchState.Loaded(JourneyService.fetchState(serverUrl, id))
            } catch (error: Throwable) {
              FetchState.Failed(error.message ?: error::class.java.simpleName)
            }
          }
        },
      )

      Spacer(Modifier.heightIn(min = 4.dp))

      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(onClick = onStartOver) { Text("Start Over") }
      }
    }
  }
}

@Composable
private fun FetchOutcomeSection(
  state: FetchState,
  canFetch: Boolean,
  onFetch: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = onFetch,
        enabled = canFetch && state !is FetchState.Loading,
      ) {
        Text(
          text = when (state) {
            is FetchState.Loaded -> "Fetch again"
            else -> "Fetch outcome from Captain"
          },
        )
      }
      if (state is FetchState.Loading) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
        )
      }
    }

    if (!canFetch) {
      Text(
        text = "No instance ID was provided in the terminal event, so the " +
          "outcome can't be fetched.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    when (state) {
      FetchState.Idle, FetchState.Loading -> Unit
      is FetchState.Loaded -> ResultBlock(text = state.prettyJson, isError = false)
      is FetchState.Failed -> ResultBlock(text = state.message, isError = true)
    }
  }
}

@Composable
private fun ResultBlock(text: String, isError: Boolean) {
  val bg = if (isError) {
    MaterialTheme.colorScheme.errorContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val fg = if (isError) {
    MaterialTheme.colorScheme.onErrorContainer
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(color = bg, shape = RoundedCornerShape(8.dp))
      .padding(12.dp),
  ) {
    Text(
      text = text,
      color = fg,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
    )
  }
}

@Composable
private fun StatusPill(status: JourneyTerminalStatus) {
  val (bg, fg) = when (status) {
    JourneyTerminalStatus.COMPLETED -> Color(0xFF1B5E20) to Color.White
    JourneyTerminalStatus.FAILED -> MaterialTheme.colorScheme.error to Color.White
    JourneyTerminalStatus.ABANDONED -> Color(0xFFB26A00) to Color.White
  }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(color = bg, shape = RoundedCornerShape(8.dp))
      .padding(horizontal = 16.dp, vertical = 14.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text = status.label,
      color = fg,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun IdRow(label: String, value: String?) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value.takeUnless { it.isNullOrBlank() } ?: "—",
      style = MaterialTheme.typography.bodyMedium,
      fontFamily = FontFamily.Monospace,
    )
  }
}
