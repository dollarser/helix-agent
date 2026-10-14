package com.helix.app.connector

import com.helix.app.R
import com.helix.app.mcp.McpSchemaCompatibilityException
import com.helix.extensions.mcp.McpCredentialFormatException
import com.helix.extensions.mcp.McpMetadataLimitException
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorConnectionFailureTest {
    @Test
    fun schemaFailureIsDistinctFromAuthorization() {
        assertEquals(
            R.string.connector_schema_unsupported,
            connectorConnectionFailureMessage(McpSchemaCompatibilityException(), true),
        )
    }

    @Test
    fun metadataLimitFailureDoesNotSuggestNewAuthorization() {
        for (oauth in listOf(false, true)) {
            assertEquals(
                R.string.connector_metadata_limit_failed,
                connectorConnectionFailureMessage(McpMetadataLimitException(), oauth),
            )
        }
    }

    @Test
    fun credentialFailureIsDistinctForBothAuthenticationModes() {
        for (oauth in listOf(false, true)) {
            assertEquals(
                R.string.connector_credential_format_failed,
                connectorConnectionFailureMessage(McpCredentialFormatException(), oauth),
            )
        }
    }

    @Test
    fun postAuthorizationFailureDoesNotAskUserToLogInAgain() {
        assertEquals(
            R.string.connector_oauth_connection_failed,
            connectorConnectionFailureMessage(IllegalStateException("sensitive server detail"), true),
        )
    }

    @Test
    fun otherFailuresRemainGenericWithoutRenderingExceptionMessages() {
        assertEquals(
            R.string.connector_connection_failed,
            connectorConnectionFailureMessage(IllegalStateException("sensitive server detail"), false),
        )
    }
}
