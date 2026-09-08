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

class SkillInstallationDeviceTest {
    @Test
    @Suppress("LongMethod") // One production approval flow with cleanup on every exit.
    fun planRejectsAndApprovedHashCannotInstallChangedBytes() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val author = requireNotNull(container.skillAuthoringService)
        val name = "install-device-${System.nanoTime()}"
        val path = author.saveDraft(name, "Device test", "Body")
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
                        "skills.install",
                        """{"path":"$path","expectedHash":"$hash"}""",
                        mode = chat.runControl.value.mode,
                    )
                }
        }
        try {
            chat.setMode(AgentMode.PLAN)
            val oldHash = author.preview(path).snapshotHash
            assertTrue(dispatch(oldHash).second.get(20, TimeUnit.SECONDS) is ToolDispatchOutcome.Denied)
            assertFalse(container.skillRepository.list().any { it.key.name == name })
            chat.setMode(AgentMode.ACT)
            val pending = dispatch(oldHash)
            val approval = awaitApproval(container, pending.first)
            val draft = author.loadDraft(path)
            author.saveEditedDraft(path, draft.manifest.replace("Body", "Changed"), draft.contentHash)
            chat.approveApproval(approval)
            assertFalse(pending.second.get(20, TimeUnit.SECONDS) is ToolDispatchOutcome.Succeeded)
            assertFalse(container.skillRepository.list().any { it.key.name == name })
            val accepted = dispatch(author.preview(path).snapshotHash)
            chat.approveApproval(awaitApproval(container, accepted.first))
            assertTrue(accepted.second.get(20, TimeUnit.SECONDS) is ToolDispatchOutcome.Succeeded)
            val key =
                container.skillRepository
                    .list()
                    .single { it.key.name == name }
                    .key
            assertFalse(requireNotNull(container.skillInstallationService).isEnabled(key))
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
            container.skillRepository.list().filter { it.key.name == name }.forEach {
                container.skillRepository.removePermanentlyForPrivacy(it.key)
            }
            val root = app.filesDir.toPath().resolve("workspaces/app/work/skills/$name")
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
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
