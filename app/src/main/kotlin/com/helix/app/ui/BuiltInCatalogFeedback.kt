package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.feature.conversation.R as ConversationR

internal fun builtInCatalogMessage(row: ProviderRowUi?): Int? =
    when {
        row?.bundled != true || row.builtInCatalog == null -> null
        row.builtInCatalog.failure != null -> R.string.builtin_model_catalog_failed
        row.builtInTaskDeclarationMissing -> ConversationR.string.builtin_model_tasks_missing
        row.builtInCatalog.checkedAt > 0 && row.conversationModels.isEmpty() -> R.string.builtin_model_catalog_empty
        else -> null
    }

@Composable
@Suppress("FunctionName")
internal fun BuiltInCatalogFeedback(row: ProviderRowUi?) {
    builtInCatalogMessage(row)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
}
