package com.helix.app.connector

import com.helix.app.R
import com.helix.extensions.mcp.McpCredentialFormatException

/** Present local failure categories without exposing exception messages or credentials. */
internal fun connectorConnectionFailureMessage(
    error: Exception,
    oauthAuthorized: Boolean,
): Int =
    when {
        error is McpCredentialFormatException -> R.string.connector_credential_format_failed
        oauthAuthorized -> R.string.connector_oauth_connection_failed
        else -> R.string.connector_connection_failed
    }
