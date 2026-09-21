package com.helix.app.mcp.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.helix.app.HelixApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SingleTask Activity handling OAuth callbacks redirected from system browser:
 * Scheme: helix://oauth/mcp/callback?code=...&state=...
 * ADR-CONNECTORS-002: One-time consumption, PKCE exchange, and secure storage in SecretStore.
 */
class McpOAuthCallbackActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        val app = application as? HelixApplication
        if (app == null) {
            finish()
            return
        }

        scope.launch {
            val coordinator = app.appContainer.mcpOAuthCoordinator
            if (coordinator == null) {
                finish()
                return@launch
            }

            val result =
                withContext(Dispatchers.IO) {
                    coordinator.handleCallback(uri)
                }
            when (result) {
                is McpOAuthResult.Success -> {
                    Toast
                        .makeText(
                            this@McpOAuthCallbackActivity,
                            "MCP OAuth connected: ${result.serverId}",
                            Toast.LENGTH_SHORT,
                        ).show()
                }

                is McpOAuthResult.Failure -> {
                    Toast
                        .makeText(
                            this@McpOAuthCallbackActivity,
                            "MCP OAuth failed: ${result.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
            finish()
        }
    }
}
