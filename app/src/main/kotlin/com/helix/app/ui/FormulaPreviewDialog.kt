package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.helix.app.R
import java.util.UUID

/** On-demand keeps chat scrolling free of per-message WebViews; source and copy remain canonical. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun FormulaPreviewDialog(
    source: String,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val config = LocalConfiguration.current
    val clipboard = LocalClipboardManager.current
    val valid = source.isNotBlank() && source.length <= FormulaPreviewPolicy.MAX_CHARS && '\u0000' !in source
    var state by remember(source) { mutableStateOf(if (valid) "loading" else "error") }
    val html =
        remember(source, colors.surface, colors.onSurface, config.fontScale) {
            if (valid) {
                FormulaPreviewPolicy.html(
                    source,
                    colors.surface.toArgb(),
                    colors.onSurface.toArgb(),
                    (16f * config.fontScale).coerceIn(10f, 64f),
                    UUID.randomUUID().toString().replace("-", ""),
                )
            } else {
                ""
            }
        }
    var contentHeight by remember(html) { mutableStateOf(96f) }
    val windowHeight =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
                .value
        }
    val maximumHeight = (windowHeight * .45f).coerceIn(120f, 360f)
    LaunchedEffect(html) {
        state = if (valid) "loading" else "error"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.delay(5000)
        }
        if (state == "loading") state = "error"
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.formula_title)) },
        text = {
            Column {
                if (state == "error") {
                    Text(stringResource(R.string.formula_error), Modifier.testTag("formula-error"))
                    Text(
                        source.take(FormulaPreviewPolicy.MAX_CHARS),
                        Modifier.height(160.dp).verticalScroll(rememberScrollState()),
                    )
                } else {
                    if (state ==
                        "loading"
                    ) {
                        Text(stringResource(R.string.formula_loading), Modifier.testTag("formula-loading"))
                    }
                    key(html) {
                        AndroidView(
                            factory = { context ->
                                FormulaPreviewWebView(context, html, { contentHeight = it }) { state = it }.apply {
                                    setBackgroundColor(colors.surface.toArgb())
                                    loadUrl(FormulaPreviewPolicy.PAGE)
                                }
                            },
                            onRelease = { it.release() },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(contentHeight.coerceIn(64f, maximumHeight).dp)
                                    .testTag("formula-webview"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.formula_close)) } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(source)) }) {
                Text(stringResource(R.string.formula_copy))
            }
        },
        modifier = Modifier.testTag("formula-dialog"),
    )
}

@Composable
@Suppress("FunctionName")
internal fun FormulaPreviewEntry(
    source: String,
    language: String?,
) {
    if (!language.equals("math", ignoreCase = true)) return
    var open by remember(source) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("formula-preview-open")) {
        Text(stringResource(R.string.formula_open))
    }
    if (open) FormulaPreviewDialog(source) { open = false }
}
