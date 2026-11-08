package com.helix.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay

@Composable
@Suppress("FunctionName")
internal fun PreviewControlsAutoHide(
    visible: Boolean,
    pointerDown: Boolean,
    focused: Boolean,
    onHide: () -> Unit,
) {
    val hide by rememberUpdatedState(onHide)
    val timeout =
        LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = 4_000,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: 4_000L
    LaunchedEffect(visible, pointerDown, focused, timeout) {
        if (visible && !pointerDown && !focused) {
            delay(timeout)
            hide()
        }
    }
}

/** Observe child controls too, without stealing taps, transforms or corner drags. */
@Composable
internal fun Modifier.previewPointerActivity(onActivity: (Boolean) -> Unit): Modifier {
    val activity by rememberUpdatedState(onActivity)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            activity(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                activity(false)
            }
        }
    }
}
