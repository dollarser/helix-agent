package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** Spacious editor with persistent actions and one independently scrolling body. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ProviderEditorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        Surface(
            modifier =
                modifier
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(8.dp)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        dismissButton()
                        confirmButton()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).padding(16.dp)) { text() }
            }
        }
    }
}
