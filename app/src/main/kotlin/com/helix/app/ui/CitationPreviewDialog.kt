package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.core.model.citation.Citation
import com.helix.core.model.citation.CitationContent
import kotlinx.coroutines.CancellationException
import com.helix.ui.shared.R as SharedR

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun CitationPreviewDialog(
    citation: Citation,
    resolve: suspend (Citation) -> CitationContent?,
    onDismiss: () -> Unit,
) {
    var loaded by remember(citation) { mutableStateOf(false) }
    var content by remember(citation) { mutableStateOf<CitationContent?>(null) }
    LaunchedEffect(citation) {
        try {
            content = resolve(citation)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            content = null
        }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(content?.title ?: stringResource(R.string.citation_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("citation-preview")) {
                Text(stringResource(R.string.citation_range, citation.start, citation.end))
                content?.page?.let { Text(stringResource(R.string.citation_page, it)) }
                SelectionContainer {
                    Text(
                        if (content?.noText == true) {
                            stringResource(R.string.citation_no_text)
                        } else {
                            content?.excerpt ?: stringResource(
                                if (loaded) R.string.citation_unavailable else R.string.citation_loading,
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(SharedR.string.common_cancel)) }
        },
    )
}
