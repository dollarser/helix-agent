package com.helix.app.knowledge

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.helix.core.storage.repository.KnowledgeOrigin
import com.helix.ui.shared.SharedSheet

/** Explicitly saved provenance remains a frozen excerpt, separate from live chat read authority. */
@Composable
@Suppress("FunctionName")
internal fun KnowledgeOriginChips(origins: List<KnowledgeOrigin>) {
    var selected by remember(origins) { mutableStateOf<KnowledgeOrigin?>(null) }
    origins.forEach { origin -> TextButton(onClick = { selected = origin }) { Text(origin.title) } }
    selected?.let { origin ->
        SharedSheet(origin.title, "knowledge-origin", { selected = null }) {
            Text(
                androidx.compose.ui.res
                    .stringResource(referenceKindLabel(origin.kind)),
            )
            Text(
                androidx.compose.ui.res
                    .stringResource(com.helix.app.R.string.knowledge_origin_frozen),
            )
            SelectionContainer { Text(origin.text) }
        }
    }
}
