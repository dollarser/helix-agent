package com.helix.app.connector

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OAuthClientSetupDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun registrationNeedsSeparateConfirmationShowingExactTargets() {
        var registrations = 0
        val offer = offer(false)
        compose.setContent {
            MaterialTheme {
                var confirming by remember { mutableStateOf(false) }
                Column {
                    OAuthClientSetupOptions(offer, true, "", {}, {}, { confirming = true }, {})
                    if (confirming) {
                        OAuthClientSetupConfirmation(offer, false, { confirming = false }) {
                            registrations++
                            confirming = false
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, registrations) }
        compose.onNodeWithTag("oauth-client-register").performClick()
        compose.runOnIdle { assertEquals(0, registrations) }
        compose.onNodeWithText(offer.metadata.issuer).assertExists()
        compose.onNodeWithText(requireNotNull(offer.metadata.registrationEndpoint)).assertExists()
        compose.onNodeWithText(offer.redirect).assertExists()
        compose.onNodeWithTag("oauth-client-confirm").performClick()
        compose.runOnIdle { assertEquals(1, registrations) }
    }

    @Test fun cimdAdvertisingDoesNotExposeDynamicRegistration() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                Column { OAuthClientSetupOptions(offer(true), true, "", {}, { requests++ }, { requests++ }, {}) }
            }
        }
        compose.onNodeWithTag("oauth-client-register").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    private fun offer(cimd: Boolean) =
        OAuthClientSetupOffer(
            "server",
            "https://resource.example/mcp",
            "helix://oauth/mcp/callback",
            McpOAuthServerMetadata(
                "https://issuer.example",
                "https://issuer.example/auth",
                "https://issuer.example/token",
                registrationEndpoint = "https://issuer.example/register",
                clientIdMetadataDocumentSupported = cimd,
            ),
        )
}
