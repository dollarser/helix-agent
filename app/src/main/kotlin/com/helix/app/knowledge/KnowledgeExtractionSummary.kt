package com.helix.app.knowledge

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.core.storage.knowledge.CollectedSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
@Suppress("FunctionName")
internal fun KnowledgeExtractionSummary(source: CollectedSource) {
    if (!source.filesJson.contains("application/pdf")) return
    val reports =
        remember(source.extraction) {
            runCatching {
                val entries = Json.parseToJsonElement(source.extraction) as JsonArray
                entries.map { it as JsonObject }
            }.getOrNull()
        }
    Text(stringResource(R.string.knowledge_pdf_text_only), style = MaterialTheme.typography.bodySmall)
    reports?.forEach { report ->
        val processed = report["processedPages"]?.jsonPrimitive?.intOrNull ?: 0
        val total = report["totalPages"]?.jsonPrimitive?.intOrNull
        Text(
            if (total == null) {
                stringResource(R.string.knowledge_pdf_coverage_unknown)
            } else {
                stringResource(R.string.knowledge_pdf_coverage, processed, total)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    } ?: Text(stringResource(R.string.knowledge_pdf_coverage_unknown), style = MaterialTheme.typography.bodySmall)
}
