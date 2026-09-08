package com.helix.app

import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.AgentMode
import com.helix.tools.framework.ToolDispatchOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ConnectorInstallationDeviceTest {
    @Test
    @Suppress("LongMethod") // One production approval flow with cleanup on every exit.
    fun planRejectsAndApprovedHashCannotInstallChangedBytes() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val author = requireNotNull(container.connectorInstallationService)
        val name = "install-device-${System.nanoTime()}"
        val file = app.filesDir.toPath().resolve("workspaces/app/work/$name.json")
        Files.write(
            file,
            """{"docs":{"url":"https://example.test/$name","headers":{"Authorization":"fixture"}}}""".toByteArray(),
        )
        val path = "scope:app:work/$name.json"
        val chat = container.chatService
        val previous = chat.runControl.value
        val session = "installer-session-${System.nanoTime()}"
        container.storage.sessions.create(session, "Installer test", null, null, System.currentTimeMillis())
        chat.openSession(session)

        fun dispatch(hash: String): Pair<String, CompletableFuture<ToolDispatchOutcome>> {
            val id = "installer-${System.nanoTime()}"
            container.storage.turns.start(id, session, System.currentTimeMillis())
            return id to
                CompletableFuture.supplyAsync {
                    chat.dispatchToolCall(
                        id,
                        id,
                        "connectors.install",
                        """{"path":"$path","expectedHash":"$hash"}""",
                        mode = chat.runControl.value.mode,
                    )
                }
        }
        try {
            chat.setMode(AgentMode.PLAN)
            val previewTurn = "preview-${System.nanoTime()}"
            container.storage.turns.start(previewTurn, session, System.currentTimeMillis())
            assertTrue(
                chat.dispatchToolCall(
                    previewTurn,
                    previewTurn,
                    "connectors.preview",
                    """{"path":"$path"}""",
                    mode = AgentMode.PLAN,
                ) is ToolDispatchOutcome.Succeeded,
            )
            val preview = author.preview(path)
            assertTrue(preview.endpoints.single().needsCredential)
            assertFalse(preview.toString().contains("fixture"))
            val oldHash = preview.contentHash
            assertTrue(dispatch(oldHash).second.get(20, TimeUnit.SECONDS) is ToolDispatchOutcome.Denied)
            assertFalse(
                container.connectorService.list().any {
                    it.endpoints.any { endpoint ->
                        name in
                            endpoint.endpoint.url
                    }
                },
            )
            chat.setMode(AgentMode.ACT)
            val pending = dispatch(oldHash)
            val approval = awaitApproval(container, pending.first)
            Files.write(file, """{"docs":{"url":"https://example.test/$name-changed"}}""".toByteArray())
            chat.approveApproval(approval)
            assertFalse(pending.second.get(20, TimeUnit.SECONDS) is ToolDispatchOutcome.Succeeded)
            assertFalse(
                container.connectorService.list().any {
                    it.endpoints.any { endpoint ->
                        name in
                            endpoint.endpoint.url
                    }
                },
            )
            val accepted = dispatch(author.preview(path).contentHash)
            chat.approveApproval(awaitApproval(container, accepted.first))
            assertTrue(accepted.second.get(20, TimeUnit.SECONDS) is ToolDispatchOutcome.Succeeded)
            val record = container.connectorService.list().single { it.hash == author.preview(path).contentHash }
            assertFalse(container.connectorService.enabled(record.endpoints.single()))
            org.junit.Assert.assertEquals(record, author.install(path, record.hash))
            org.junit.Assert.assertThrows(
                IllegalStateException::class.java,
            ) { author.install(path, record.hash) { true } }
            assertTrue(
                container.storage.mcpServers
                    .list()
                    .none { it.id == record.endpoints.single().id },
            )
        } finally {
            container.storage.turns
                .listBySession(session)
                .flatMap { container.storage.toolCalls.listByTurn(it.id) }
                .filter { it.state == "AWAITING_APPROVAL" }
                .forEach {
                    container.storage.approvals.byToolCall(it.callId)?.let { approval ->
                        container.toolPipeline.broker.cancel(approval.id)
                    }
                }
            chat.closeSession()
            chat.setMode(previous.mode)
            container.connectorService
                .list()
                .filter {
                    it.endpoints.any { endpoint -> name in endpoint.endpoint.url }
                }.forEach { container.connectorService.remove(it) }
            Files.deleteIfExists(file)
        }
    }

    private fun awaitApproval(
        container: AppContainer,
        call: String,
    ): String {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            container.storage.approvals
                .byToolCall(call)
                ?.let { return it.id }
            Thread.sleep(50)
        }
        error("No exact approval for $call")
    }
}
