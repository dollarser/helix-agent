package com.helix.app.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R

internal fun pluginTitleCase(name: String): String =
    name
        .split(Regex("[-_\\s]+"))
        .filter(String::isNotEmpty)
        .joinToString(" ") { it.replaceFirstChar { letter -> letter.titlecase(java.util.Locale.ROOT) } }

/** Presentation only; registry names, package identities and model tool schemas remain unchanged. */
@Composable
internal fun pluginLabel(name: String): String =
    when (name.lowercase(java.util.Locale.ROOT)) {
        "mobile-use", "mobile use" -> stringResource(R.string.plugin_name_mobile_use)
        "browser" -> stringResource(R.string.plugin_name_browser)
        "chat-reply", "chat reply" -> stringResource(R.string.plugin_name_chat_reply)
        "documents" -> stringResource(R.string.document_plugin_name)
        else -> pluginTitleCase(name)
    }
