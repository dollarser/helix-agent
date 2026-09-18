package com.helix.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * HXA-191: the single Compose theming root for the app.
 *
 * This is REQUIRED, not cosmetic. In material3 1.4.0 the `MaterialTheme` composable defaults
 * `colorScheme` to `MaterialTheme.colorScheme` (= `LocalColorScheme.current`), and
 * `LocalColorScheme` is `staticCompositionLocalOf { lightColorScheme() }` (see
 * `ColorScheme.kt`). A bare `MaterialTheme {}` therefore resolves to a FIXED LIGHT scheme even
 * when the system is in dark mode — the Compose surfaces would never follow the system. We wire
 * the scheme EXPLICITLY to the real system night mode here so every Compose surface (the main
 * shell, the first-launch notice, dialogs, and each destination) tracks the system. The Android
 * window side (status/nav bars + the pre-Compose background) is the values-night platform theme,
 * the counterpart of this scheme — neither side alone gives a correct dark theme.
 */
@Suppress("FunctionName")
@Composable
fun HelixTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
