// SelfieStubScreen.kt
//
// A Compose stub for the selfie capture step. Mirrors iOS's
// `StubSelfieCameraView` (SDK-bundled) which the iOS reference relies on
// when Smart Capture is not enabled. The Android Bridge SDK does not yet
// ship a stub camera surface (typed-slot work is deferred — see
// PARITY.md), so the stub UI lives in the reference app instead.
//
// The user can:
//   - Use a synthetic placeholder image (no camera needed — handy on
//     emulators where the front camera is mocked / unavailable)
//   - Pick an existing image from the photo library
//   - Cancel and return a `cancelled` status to the journey

package com.gbg.go.reference.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SelfieStubScreen(
  onUseStubImage: () -> Unit,
  onPickFromLibrary: () -> Unit,
  onCancel: () -> Unit,
) {
  Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
    ),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth(0.6f)
            .clip(CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
          ) {
            IconButton(onClick = {}, enabled = false) {
              Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        }

        Text(
          text = "Selfie Capture (Stub)",
          style = MaterialTheme.typography.headlineSmall,
        )
        Text(
          text = "The Android Bridge SDK does not yet ship a stub selfie " +
            "camera. Pick a placeholder image or load one from the photo " +
            "library to complete the journey's selfie step.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
          onClick = onUseStubImage,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Use Placeholder Image")
        }
        OutlinedButton(
          onClick = onPickFromLibrary,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Pick From Photo Library")
        }
        TextButton(
          onClick = onCancel,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Cancel")
        }
      }
    }
  }
}
