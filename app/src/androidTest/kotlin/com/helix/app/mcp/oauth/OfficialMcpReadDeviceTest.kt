package com.helix.app.mcp.oauth

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.policy.SessionPermissionConfig
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Owner opt-in, exact supplied object, existing Helix credentials; never log account content or tokens. */
class OfficialMcpReadDeviceTest {
    private val c get() = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer

    @Test
    @Suppress("LongMethod") // One bounded real-account read/denial with restoration of the original tool selection.
    fun suppliedObjectReadAndApprovalDenial() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.containsKey("officialOAuthService"))
            val vendor = requireNotNull(args.getString("officialOAuthService"))
            val objectRef = requireNotNull(args.getString("officialOAuthObject"))
            val target = target(vendor, objectRef)
            val service = c.pluginService
            val record = service.list().single { plugin -> plugin.endpoints.any { it.endpoint.url == target.endpoint } }
            val endpoint = record.endpoints.single { it.endpoint.url == target.endpoint }
            check(service.hasOAuthToken(endpoint)) { "Existing OAuth authorization is required" }
            val originalEnabled =
                c.storage.mcpServers
                    .resolve(endpoint.id)
                    .enabled
            val originalSelection =
                c.storage.mcpCapabilities
                    .listByServer(endpoint.id)
                    .filter { it.kind == "tool" && it.enabled }
                    .mapTo(linkedSetOf()) { it.name }
            val before = credential(endpoint.id)
            val offer = service.clientSetup.inspect(endpoint, service.oauthRedirectUri)
            val remembered = requireNotNull(service.clientSetup.remembered(offer))
            val reused =
                requireNotNull(service.oauthCoordinator).clients.resolve(
                    endpoint.id,
                    offer.metadata,
                    offer.redirect,
                    "",
                )
            assertEquals(remembered, reused)
            assertEquals(before.binding.clientId, reused.clientId)
            val expiredBefore = before.tokens.isExpired()
            val snapshot = withTimeout(90_000) { service.testOAuth(endpoint) }
            val after = credential(endpoint.id)
            assertFalse(after.refreshing)
            if (expiredBefore) {
                check(before.tokens.refreshToken != null)
                assertTrue(
                    "Natural refresh must publish a fresh record",
                    after.tokens.issuedAtMs > before.tokens.issuedAtMs,
                )
                assertFalse(after.tokens.isExpired())
            }
            try {
                val metadata = snapshot.metadata.tools.single { it.name == target.tool }
                service.enable(endpoint, snapshot, originalSelection + target.tool)
                val descriptor =
                    c.toolPipeline.registry.all().single {
                        val origin = it.origin as? ToolOrigin.McpOrigin
                        origin?.serverId == endpoint.id && origin.sourceSchemaHash == metadata.schemaHash
                    }
                dispatch(record.id, descriptor, target, approve = false)
                dispatch(record.id, descriptor, target, approve = true)
                println(
                    "OFFICIAL_MCP_READ=PASS vendor=$vendor dispatcher=true approvalDenied=true objectMatched=true " +
                        "naturalRefresh=${if (expiredBefore) "passed" else "not_due"} " +
                        "expiryKnown=${after.tokens.expiresInSeconds != null}",
                )
            } finally {
                if (originalEnabled && originalSelection.isNotEmpty()) {
                    service.enable(endpoint, snapshot, originalSelection)
                } else {
                    service.disable(endpoint)
                }
            }
        }

    private fun credential(serverId: String) =
        OAuthCredentialCodec.decode(c.storage.secrets.get(SecretAlias("mcp.$serverId.oauth.binding")))

    @Suppress("LongMethod") // Exercise normal dispatch and exact approval while cleaning only this owned session.
    private fun dispatch(pluginId: String, descriptor: ToolDescriptor, target: ReadTarget, approve: Boolean) {
        val id = UUID.randomUUID().toString()
        val session = "official-read-$id"
        val turn = "official-turn-$id"
        val call = "official-call-$id"
        c.storage.sessions.create(session, "Owned MCP read acceptance", null, null, System.currentTimeMillis())
        c.storage.turns.start(turn, session, System.currentTimeMillis())
        c.chatService.openSession(session)
        c.pluginService.catalog.select(session, pluginId, true)
        c.sessionPermissionEdit.saveSessionConfig(
            session,
            SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
            0,
        )
        val task = FutureTask { c.chatService.dispatchToolCall(call, turn, descriptor.name.value, target.arguments) }
        val worker =
            Thread(task, "official-mcp-read").apply {
                isDaemon = true
                start()
            }
        try {
            val deadline = System.currentTimeMillis() + 30_000
            while (!task.isDone && c.storage.approvals.byToolCall(call) == null &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            val approval = checkNotNull(c.storage.approvals.byToolCall(call)) { "Expected exact MCP approval" }
            if (approve) c.chatService.approveApproval(approval.id) else c.chatService.denyApproval(approval.id)
            val result = task.get(90, TimeUnit.SECONDS)
            assertEquals(
                if (approve) "APPROVED" else "DENIED",
                c.storage.approvals
                    .resolve(approval.id)
                    .decision,
            )
            assertEquals(if (approve) 1 else 0, c.mcpService.sentSummaries(session).size)
            if (approve) {
                assertTrue("Approved read must succeed", result is ToolDispatchOutcome.Succeeded)
                val payload =
                    Json
                        .parseToJsonElement(
                            (result as ToolDispatchOutcome.Succeeded).result.payload,
                        ).jsonObject
                assertEquals("false", payload.getValue("isError").jsonPrimitive.content)
                val text =
                    payload.getValue("blocks").jsonArray.joinToString("\n") {
                        it.jsonObject["text"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    }
                assertTrue(
                    "Read must identify the supplied object",
                    text.replace("-", "").contains(target.marker.replace("-", ""), true),
                )
            } else {
                assertTrue("Denied read must not execute", result is ToolDispatchOutcome.Denied)
            }
        } finally {
            if (!task.isDone) {
                c.storage.approvals
                    .byToolCall(call)
                    ?.let { c.toolPipeline.broker.cancel(it.id) }
                task.cancel(true)
            }
            worker.join(10_000)
            check(!worker.isAlive) { "Executor cleanup incomplete; owned session retained" }
            c.toolPipeline.endTurn(turn)
            c.chatService.closeSession()
            c.storage.deleteSessionPermanently(session)
        }
    }

    private fun target(
        vendor: String,
        objectRef: String,
    ): ReadTarget =
        when (vendor) {
            "linear" -> {
                require(Regex("[A-Z][A-Z0-9]*-[1-9][0-9]*").matches(objectRef))
                ReadTarget(
                    "https://mcp.linear.app/mcp",
                    "get_issue",
                    buildJsonObject { put("id", objectRef) }.toString(),
                    objectRef,
                )
            }

            "notion" -> {
                val uri = URI(objectRef)
                require(
                    uri.scheme == "https" && uri.host == "app.notion.com" && uri.rawQuery == null &&
                        uri.rawFragment == null && uri.rawUserInfo == null,
                )
                val page = uri.path.removePrefix("/p/")
                require(uri.path == "/p/$page" && Regex("[a-f0-9]{32}").matches(page))
                ReadTarget(
                    "https://mcp.notion.com/mcp",
                    "notion-fetch",
                    buildJsonObject { put("id", objectRef) }.toString(),
                    page,
                )
            }

            else -> {
                error("Unsupported official read service")
            }
        }

    private data class ReadTarget(
        val endpoint: String,
        val tool: String,
        val arguments: String,
        val marker: String,
    )
}
