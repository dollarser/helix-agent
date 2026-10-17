package com.helix.app.mcp.oauth

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.openTestSessionControl
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.SecretAlias
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.plugin.PluginPackageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** Owner opt-in for the synthetic public account; never log codes, state, tokens or note contents. */
class PublicRemoteNotesOAuthDeviceTest {
    @get:org.junit.Rule
    val activity =
        androidx.test.ext.junit.rules
            .ActivityScenarioRule(com.helix.app.MainActivity::class.java)

    @Test
    @Suppress("LongMethod") // One owned public login/refresh/deny/clear journey with guaranteed local cleanup.
    fun nativeLoginRefreshReplayAndLocalDisconnect() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("remoteNotesOAuth") != null)
            require(args.getString("remoteNotesOAuth") == "true")
            val url = requireNotNull(args.getString("remoteNotesEndpoint"))
            val resource = URI(url)
            require(resource.scheme == "https" && resource.rawQuery == null && resource.rawUserInfo == null)
            require(resource.path == "/api/mcp")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val service = c.pluginService
            val coordinator = requireNotNull(service.oauthCoordinator)
            val name = "public-notes-${UUID.randomUUID()}"
            val record =
                service.install(
                    PluginPackageReader().parse(
                        mapOf(
                            ".codex-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                            ".mcp.json" to """{"mcp_servers":{"notes":{"url":"$url"}}}""".toByteArray(),
                        ),
                    ),
                )
            val endpoint = record.endpoints.single()
            val offer = service.clientSetup.inspect(endpoint, service.oauthRedirectUri)
            val remembered = service.clientSetup.remembered(offer)
            try {
                val identity = remembered ?: service.clientSetup.register(endpoint, offer)
                val prepared = service.prepareOAuth(endpoint, identity.clientId, "notes:read")
                val callback = consent(prepared.authUri, offer.metadata.issuer, true)
                assertTrue(deliverNativeCallback(app, coordinator, endpoint.id, callback) is McpOAuthResult.Success)
                assertTrue(service.hasOAuthToken(endpoint))
                assertTrue(coordinator.handleCallback(callback) is McpOAuthResult.Failure)
                val snapshot = service.testOAuth(endpoint)
                assertTrue(snapshot.metadata.tools.any { it.name == "notes_list" })
                service.enable(endpoint, snapshot, setOf("notes_list"))
                publicReadThroughModel(c, record.id, endpoint.id)
                // Expire only this owned encrypted record to exercise production refresh without changing server TTL.
                val alias = SecretAlias("mcp.${endpoint.id}.oauth.binding")
                val before = OAuthCredentialCodec.decode(c.storage.secrets.get(alias))
                assertTrue(before.tokens.refreshToken != null)
                val expired = before.copy(tokens = before.tokens.copy(issuedAtMs = 0))
                c.storage.secrets.put(alias, OAuthCredentialCodec.encode(expired))
                service.testOAuth(endpoint)
                val after = OAuthCredentialCodec.decode(c.storage.secrets.get(alias))
                assertNotEquals(before.tokens.accessToken, after.tokens.accessToken)
                assertNotEquals(before.tokens.refreshToken, after.tokens.refreshToken)
                assertFalse(after.refreshing)
                val revoked = service.revokeOAuth(endpoint)
                assertEquals(offer.metadata.revocationEndpoint != null, revoked.vendorRevoked)
                assertFalse(service.hasOAuthToken(endpoint))
                assertFalse(service.enabled(endpoint))
                val rejected = service.prepareOAuth(endpoint, identity.clientId, "notes:read")
                val denial = consent(rejected.authUri, offer.metadata.issuer, false)
                assertTrue(coordinator.handleCallback(denial) is McpOAuthResult.Failure)
                assertFalse(service.hasOAuthToken(endpoint))
                println(
                    "PUBLIC_NOTES_OAUTH=PASS nativePackage=${app.packageName} login=true refreshRotation=true " +
                        "readCall=true replayRejected=true deny=true localClear=true " +
                        "vendorRevoked=${revoked.vendorRevoked}",
                )
            } finally {
                coordinator.clearLocal(endpoint.id)
                if (remembered == null) service.clientSetup.forget(offer)
                service.list().filter { it.id == record.id }.forEach(service::remove)
                service.cleanupRetired()
            }
        }

    @Suppress("LongMethod") // One owned real read, exact approval and cancellation-safe cleanup.
    private suspend fun publicReadThroughModel(
        c: com.helix.app.AppContainer,
        pluginId: String,
        serverId: String,
    ) {
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
        val model = requireNotNull(args.getString("pluginSkillModel"))
        val provider =
            c.providerService.create(
                com.helix.app.provider.ProviderDraft(
                    null,
                    "Owned public MCP fixture",
                    com.helix.core.model.ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    com.helix.core.model.NormalizedEndpoint
                        .parse("http://127.0.0.1:$port/v1"),
                    model,
                    "{}",
                    false,
                    com.helix.provider.api
                        .CleartextWarning("127.0.0.1", port),
                    emptyList(),
                ),
                null,
            )
        var ownedSession: String? = null
        val controls = c.runControlStore.current
        try {
            check(c.providerService.runConnectionTest(provider) is com.helix.provider.api.ProbeOutcome.Ok)
            val session = c.chatService.createSession("Owned public MCP read", provider, model)
            ownedSession = session
            c.pluginService.selectFromUser(session, pluginId, true)
            c.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                System.currentTimeMillis(),
            )
            c.chatService.openTestSessionControl(
                session,
                AgentMode.ACT,
                TurnBudgets(maxOutputTokens = 8192),
            )
            val descriptor =
                c.toolPipeline.registry.all().single {
                    (it.origin as? com.helix.tools.framework.ToolOrigin.McpOrigin)?.serverId == serverId
                }
            c.chatService.sendTestMessage(
                session,
                "Use tools.search to discover ${descriptor.name.value}. Call it once with limit 1. " +
                    "Do not add or change notes, inspect phone or files. Report only that the read succeeded; " +
                    "do not reproduce the demo note content.",
            )
            kotlinx.coroutines.withTimeout(180_000) {
                while (true) {
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .lastOrNull()
                    if (turn != null) {
                        val calls = c.storage.toolCalls.listByTurn(turn.id)
                        val pending =
                            calls.filter {
                                it.name == descriptor.name.value && it.state == "AWAITING_APPROVAL"
                            }
                        pending.forEach {
                            val approval = requireNotNull(c.storage.approvals.byToolCall(it.callId))
                            c.chatService.approveApproval(approval.id)
                        }
                        if (TurnState.valueOf(turn.state).isTerminal) {
                            assertEquals("COMPLETED", turn.state)
                            assertEquals(1, calls.count { it.name == descriptor.name.value && it.state == "COMPLETED" })
                            break
                        }
                    }
                    kotlinx.coroutines.delay(100)
                }
            }
        } finally {
            ownedSession?.let { session ->
                c.storage.turns.listBySession(session).lastOrNull()?.let {
                    c.chatService.stopTurn(it.id)
                    kotlinx.coroutines.withTimeout(20_000) {
                        c.agentRuntime.observe(TurnId(it.id)).collect {}
                    }
                }
                c.chatService.closeSession()
                c.storage.deleteSessionPermanently(session)
            }
            c.providerService.delete(provider)
            c.runControlStore.setMode(controls.mode)
            c.runControlStore.setReasoning(controls.reasoning)
            c.runControlStore.setBudgets(controls.budgets)
        }
    }

    private suspend fun deliverNativeCallback(
        app: HelixApplication,
        coordinator: McpOAuthCoordinator,
        serverId: String,
        callback: String,
    ): McpOAuthResult =
        kotlinx.coroutines.coroutineScope {
            val pending =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    coordinator.events.first { result ->
                        when (result) {
                            is McpOAuthResult.Success -> result.serverId == serverId
                            is McpOAuthResult.Failure -> result.serverId == serverId
                        }
                    }
                }
            app.startActivity(
                android.content
                    .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(callback))
                    .setPackage(app.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            kotlinx.coroutines.withTimeout(30_000) { pending.await() }
        }

    private suspend fun consent(
        auth: String,
        issuer: String,
        approve: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URI(auth).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                check(connection.responseCode == 200) {
                    val redirect = connection.getHeaderField("Location")?.let(android.net.Uri::parse)
                    "Consent HTTP ${connection.responseCode} host=${redirect?.host} " +
                        "error=${redirect?.getQueryParameter("error")} " +
                        "description=${redirect?.getQueryParameter("error_description")?.take(200)}"
                }
                val html = connection.inputStream.use { it.readNBytes(65536).toString(Charsets.UTF_8) }
                val transactionField = Regex("name=\"transaction\" value=\"([^\"]+)\"")
                val transaction = requireNotNull(transactionField.find(html)).groupValues[1]
                val submit = URI("$issuer/oauth/authorize").toURL().openConnection() as HttpURLConnection
                try {
                    submit.instanceFollowRedirects = false
                    submit.connectTimeout = 20_000
                    submit.readTimeout = 20_000
                    submit.requestMethod = "POST"
                    submit.doOutput = true
                    submit.setRequestProperty("Origin", issuer)
                    submit.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val approval = if (approve) "&approve=yes" else ""
                    val encodedTransaction = URLEncoder.encode(transaction, "UTF-8")
                    val body = "transaction=$encodedTransaction&demo_user=demo_alice$approval"
                    submit.outputStream.use { it.write(body.toByteArray()) }
                    check(submit.responseCode == 302) { "Consent submit HTTP ${submit.responseCode}" }
                    requireNotNull(submit.getHeaderField("Location"))
                } finally {
                    submit.disconnect()
                }
            } finally {
                connection.disconnect()
            }
        }
}
