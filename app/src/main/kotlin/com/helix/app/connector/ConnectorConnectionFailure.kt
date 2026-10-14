package com.helix.app.connector

import com.helix.app.R
import com.helix.app.mcp.McpSchemaCompatibilityException
import com.helix.extensions.mcp.McpCredentialFormatException
import com.helix.extensions.mcp.McpMetadataLimitException

/** Present local failure categories without exposing exception messages or credentials. */
internal fun connectorConnectionFailureMessage(
    error: Exception,
    oauthAuthorized: Boolean,
): Int =
    when {
        error is McpCredentialFormatException -> R.string.connector_credential_format_failed
        error is McpMetadataLimitException -> R.string.connector_metadata_limit_failed
        error is McpSchemaCompatibilityException -> R.string.connector_schema_unsupported
        oauthAuthorized -> R.string.connector_oauth_connection_failed
        else -> R.string.connector_connection_failed
    }
