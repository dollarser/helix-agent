package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class DetachedJobRegistrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun registeredExecutorsUseRealChatBudgetAndImportOriginalOutput() {
        registeredJob(false)
    }

    @Test fun userActionsCollectOriginalResultWithoutCreatingToolCalls() {
        registeredJob(true)
    }

    private fun registeredJob(manual: Boolean) {
        val context = ApplicationProvider.getApplicationContext<HelixApplication>()
        ensureInstalledRuntime(context)
        val storage = context.appContainer.storage
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "registered-$id").apply { mkdirs() }
        val workspace = WorkspaceArtifactStore({ root.toPath() }).also { it.ensureLayout("app") }
        val owner = ExecutionOwnership(ExecutionOwnershipStore(File(root, "owner")))
        val registry = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        var budgetAccesses = 0
        val userActions =
            DetachedJobRegistration.register(context, storage, workspace, registry, implementations, owner, {
                budgetAccesses++
                context.appContainer.chatService
            }, { LinuxRuntimeGate.READY }, { emptySet() })
        assertEquals(0, budgetAccesses)
        storage.sessions.create(id, "Registered detached job", null, null, 1)
        storage.sessionPermissionConfigs.setForSession(
            id,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            1,
        )
        storage.turns.start(id, id, 2)
        val args = originalArguments()
        storage.toolCalls.append(id, id, id, DetachedJobTools.START, "1", args.toString(), "RUNNING")
        val start =
            ExecutableToolCall(
                id,
                DetachedJobTools.START,
                "1",
                args,
                ExecutionTargetType.LOCAL_PROOT,
                Instant.now().plusSeconds(30),
                NoCancellation,
                id,
                id,
            )
        try {
            if (manual) {
                exerciseUserActions(implementations, owner, start, root, userActions)
                assertEquals(1, storage.toolCalls.listByTurn(id).size)
            } else {
                exercise(implementations, owner, start, root)
            }
            assertEquals(2, budgetAccesses)
        } finally {
            val binding = ProotJobBindingStore(storage).resolveDetached(id, id)
            com.helix.runtime.proot.client
                .DetachedJobClient(context)
                .cancel(binding)
            storage.sessions.archive(id, 3)
            root.deleteRecursively()
        }
    }

    private fun originalArguments() =
        buildJsonObject {
            put("script", "printf registered-result > result.txt")
            put("output", "scope:app:output/result.txt")
            put("leaseSeconds", 10)
        }

    private fun exercise(
        implementations: ToolImplementationRegistry,
        owner: ExecutionOwnership,
        start: ExecutableToolCall,
        root: File,
    ) {
        val accepted = execute(implementations, owner, start) as ToolExecutorResult.Completed
        assertEquals(
            "true",
            accepted.output.jsonObject
                .getValue("accepted")
                .jsonPrimitive.content,
        )
        assertTrue(owner.retainedOwner() != null)
        assertNull(owner.acquire("unrelated"))
        val control =
            start.copy(
                toolCallId = "control-${start.toolCallId}",
                toolName = DetachedJobTools.STATUS,
                args = buildJsonObject { put("originalCallId", start.toolCallId) },
            )
        val until = android.os.SystemClock.elapsedRealtime() + 20_000
        var terminal = false
        while (!terminal && android.os.SystemClock.elapsedRealtime() < until) {
            val state = execute(implementations, owner, control) as ToolExecutorResult.Completed
            terminal = state.output.jsonObject
                .getValue("terminal")
                .jsonPrimitive.content == "true"
            if (!terminal) Thread.sleep(100)
        }
        assertTrue(terminal)
        assertTrue(
            execute(implementations, owner, control.copy(toolName = DetachedJobTools.COLLECT))
                is ToolExecutorResult.Completed,
        )
        assertEquals("registered-result", File(root, "output/result.txt").readText())
        assertNull(owner.retainedOwner())
    }

    private fun execute(
        registry: ToolImplementationRegistry,
        owner: ExecutionOwnership,
        call: ExecutableToolCall,
    ) = owner.guard(registry.resolve(ToolName(call.toolName), ToolVersion(1))).execute(call)

    private fun exerciseUserActions(
        implementations: ToolImplementationRegistry,
        owner: ExecutionOwnership,
        start: ExecutableToolCall,
        root: File,
        actions: DetachedJobUserActions,
    ) {
        assertTrue(execute(implementations, owner, start) is ToolExecutorResult.Completed)
        val job =
            BackgroundJobUi(
                start.toolCallId,
                requireNotNull(start.turnId),
                requireNotNull(start.sessionId),
                "Original Job",
                CommandDetailState.SUBMITTED,
                true,
            )
        val until = android.os.SystemClock.elapsedRealtime() + 20_000
        var result = BackgroundJobActionOutcome.ACTIVE
        while (result == BackgroundJobActionOutcome.ACTIVE && android.os.SystemClock.elapsedRealtime() < until) {
            result = actions.perform(job, BackgroundJobAction.QUERY) { false }
            if (result == BackgroundJobActionOutcome.ACTIVE) Thread.sleep(100)
        }
        assertEquals(BackgroundJobActionOutcome.TERMINAL_PENDING, result)
        assertTrue(owner.retainedOwner() != null)
        assertEquals(
            BackgroundJobActionOutcome.SETTLED,
            actions.perform(job, BackgroundJobAction.COLLECT) { false },
        )
        assertEquals("registered-result", File(root, "output/result.txt").readText())
        assertNull(owner.retainedOwner())
    }
}
