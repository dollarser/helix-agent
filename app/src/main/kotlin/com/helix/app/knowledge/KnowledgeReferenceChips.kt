package com.helix.app.knowledge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.storage.repository.KnowledgeReference

/** Same preview for editable drafts and immutable historical message citations. */
@Composable
@Suppress("FunctionName")
fun KnowledgeReferenceChips(
    references: List<KnowledgeReference>,
    service: KnowledgeReferenceService? = null,
    onChange: ((List<KnowledgeReference>) -> Unit)? = null,
) {
    if (references.isEmpty()) return
    var open by remember { mutableStateOf<String?>(null) }
    val states =
        remember(service, references) { service?.states(references) }
            ?.collectAsStateWithLifecycle(emptyMap())
            ?.value
            .orEmpty()
    val selected = references.firstOrNull { "${it.kind}:${it.resourceId}" == open }
    selected?.let { item ->
        KnowledgeReferencePreview(
            item,
            service,
            onChange =
                onChange?.let { change ->
                    { replacement -> change(references.map { if (it == item) replacement else it }) }
                },
            onDismiss = { open = null },
        )
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("knowledge-reference-chips")) {
        Text(
            stringResource(R.string.knowledge_reference_count, references.size, references.sumOf { it.text.length }),
            style = MaterialTheme.typography.labelSmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            references.forEach { item ->
                val state = states["${item.kind}:${item.resourceId}"]
                val warning =
                    state == KnowledgeReferenceService.State.UNAVAILABLE ||
                        (state == KnowledgeReferenceService.State.CHANGED && !item.keepVersion)
                KnowledgeReferenceChip(
                    item,
                    state,
                    warning,
                    onOpen = { open = "${item.kind}:${item.resourceId}" },
                    onRemove = onChange?.let { change -> { change(references - item) } },
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeReferenceChip(
    item: KnowledgeReference,
    state: KnowledgeReferenceService.State?,
    warning: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    InputChip(
        selected = false,
        shape = KnowledgeActionShape,
        onClick = onOpen,
        modifier = Modifier.widthIn(max = 230.dp).testTag("knowledge-citation-${item.resourceId}"),
        leadingIcon = {
            Icon(
                painterResource(R.drawable.ic_knowledge_reference),
                null,
                Modifier.size(16.dp),
                tint =
                    if (warning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        label = {
            KnowledgeReferenceLabel(item, state)
        },
        trailingIcon =
            if (onRemove == null) {
                null
            } else {
                (
                    {
                        IconButton(
                            onClick = onRemove,
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .testTag("knowledge-citation-remove-${item.resourceId}"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                stringResource(R.string.knowledge_reference_remove),
                                Modifier.size(16.dp),
                            )
                        }
                    }
                )
            },
    )
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeReferenceLabel(
    item: KnowledgeReference,
    state: KnowledgeReferenceService.State?,
) {
    val status =
        when {
            state == KnowledgeReferenceService.State.UNAVAILABLE -> {
                R.string.knowledge_ref_missing_short
            }

            state == KnowledgeReferenceService.State.CHANGED && !item.keepVersion -> {
                R.string.knowledge_ref_updated_short
            }

            else -> {
                null
            }
        }
    Text(
        item.title + (status?.let { " · " + stringResource(it) } ?: ""),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
