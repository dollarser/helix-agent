package com.helix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.helix.app.R

internal val LocalPageReturn = staticCompositionLocalOf<(() -> Unit)?> { null }

/** For local editors without their own save/leave dialog. Cancellation never commits the draft. */
@Composable
@Suppress("FunctionName")
internal fun UnsavedNavigationGuard(
    dirty: Boolean,
    busy: Boolean,
    onBack: (() -> Unit)? = null,
    discard: () -> Unit,
) {
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val back = onBack ?: LocalPageReturn.current
    if (dirty || busy) {
        NavigationLeaveGuard { complete ->
            if (busy) complete(false) else pending = complete
        }
    }
    BackHandler(enabled = dirty || busy) {
        if (!busy && back != null) pending = { if (it) back() }
    }
    if (pending != null) {
        val cancel = {
            pending?.invoke(false)
            pending = null
        }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(stringResource(R.string.navigation_discard_title)) },
            confirmButton = {
                TextButton({
                    discard()
                    pending?.invoke(true)
                    pending = null
                }) { Text(stringResource(R.string.common_discard_edit)) }
            },
            dismissButton = {
                TextButton(cancel) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
