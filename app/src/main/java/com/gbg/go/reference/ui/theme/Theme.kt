// Theme.kt
//
// A minimal Material 3 theme for the reference app. The point of the
// reference build is to demonstrate the GBG Go bridge integration cleanly,
// not to ship an opinionated design system — so the theme is intentionally
// stock M3 with no custom palette work. Replace freely in your own app.

package com.gbg.go.reference.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun GBGGoReferenceTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
