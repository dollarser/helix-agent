package com.helix.feature.conversation.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.helix.feature.conversation.R

@Composable
internal fun toolPayloadLabels(): (String) -> String {
    val labels =
        mapOf(
            "name" to stringResource(R.string.tool_field_name),
            "source" to stringResource(R.string.tool_field_source),
            "path" to stringResource(R.string.tool_field_path),
            "content" to stringResource(R.string.tool_field_content),
            "instructions" to stringResource(R.string.tool_field_instructions),
            "description" to stringResource(R.string.tool_field_description),
            "status" to stringResource(R.string.tool_field_status),
            "error" to stringResource(R.string.tool_field_error),
            "message" to stringResource(R.string.tool_field_message),
            "text" to stringResource(R.string.tool_field_text),
            "packageName" to stringResource(R.string.tool_field_application),
            "enabled" to stringResource(R.string.tool_field_enabled),
            "entries" to stringResource(R.string.tool_field_entries),
            "nodes" to stringResource(R.string.tool_field_nodes),
            "truncated" to stringResource(R.string.tool_field_truncated),
            "observationStatus" to stringResource(R.string.tool_field_observation),
            "yes" to stringResource(R.string.approval_yes),
            "no" to stringResource(R.string.approval_no),
            "empty" to stringResource(R.string.tool_field_empty),
            "none" to stringResource(R.string.tool_field_none),
        )
    return remember(labels) {
        { key -> labels[key] ?: key.replace(FIELD_WORD_BOUNDARY, "$1 $2").replace('_', ' ') }
    }
}

private val FIELD_WORD_BOUNDARY = Regex("([a-z])([A-Z])")
