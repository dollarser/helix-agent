package com.helix.app.knowledge

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

internal val KnowledgeActionShape =
    androidx.compose.foundation.shape
        .RoundedCornerShape(12.dp)

/** Reading and editing share navigation, never a stack of management controls above the text. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun KnowledgeHeader(
    title: String,
    backTag: String,
    onBack: () -> Unit,
    onMore: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.testTag(backTag)) {
            Icon(
                painterResource(R.drawable.ic_drawer_chevron),
                stringResource(R.string.knowledge_back),
                Modifier.graphicsLayer { rotationZ = 180f },
            )
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (onSave != null) {
            TextButton(onSave, enabled = saveEnabled, modifier = Modifier.testTag("knowledge-note-save")) {
                Text(stringResource(R.string.common_save))
            }
        } else if (onMore != null) {
            IconButton(onMore, Modifier.testTag("knowledge-note-management")) {
                Icon(painterResource(R.drawable.ic_more_vertical), stringResource(R.string.knowledge_more))
            }
        }
    }
}

internal fun knowledgeDate(epoch: Long): String =
    java.text.DateFormat
        .getDateInstance(java.text.DateFormat.MEDIUM)
        .format(java.util.Date(epoch))
