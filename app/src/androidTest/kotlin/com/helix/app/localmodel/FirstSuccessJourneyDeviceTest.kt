package com.helix.app.localmodel

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** P4: one real on-device model journey, followed by a new-process read-only verification. */
@RunWith(AndroidJUnit4::class)
class FirstSuccessJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod")
    fun firstSuccessSurvivesProcessDeathWithoutReexecution() =
        runBlocking {
            if (InstrumentationRegistry.getArguments().getString("recoveryPhase")?.startsWith("setup") == true) {
                setupAndKill()
            } else {
                verifyAfterRestart()
            }
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One end-to-end setup preserves the real product sequence.
    private suspend fun setupAndKill() {
        val args = InstrumentationRegistry.getArguments()
        val port = requireNotNull(args.getString("p4ModelPort")).toInt()
        val hash = requireNotNull(args.getString("p4ModelSha"))
        val size = requireNotNull(args.getString("p4ModelSize")).toLong()
        val timeoutMs = requireNotNull(args.getString("p4TaskTimeoutSeconds")).toLong() * 1000
        require(timeoutMs in 60_000..900_000)
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val storage = container.storage
        val chat = container.chatService
        val providers = container.providerService
        val marker = marker(app)
        check(!marker.exists()) { "P4 must start from cleared app data" }

        val installed =
            providers.installLocalModelForTest(
                "http://127.0.0.1:$port/model.gguf",
                hash,
                size,
                "P4 first success",
            )
        assertTrue(installed.connection is ProbeOutcome.Ok)
        assertTrue(installed.capabilities is ProbeOutcome.Ok)

        chat.closeSession()
        val sessionId = chat.createSession("P4 first success", installed.providerId, installed.modelId)
        chat.openSession(sessionId)
        withTimeout(10_000) {
            while (
                chat.screen.value.openSessionId != sessionId ||
                chat.screen.value.badge
                    ?.model != installed.modelId
            ) {
                delay(20)
            }
        }
        val session = storage.sessions.resolve(sessionId)
        assertEquals(installed.providerId, session.providerId)
        assertEquals(installed.modelId, session.modelId)
        val binding = requireNotNull(storage.workspaces.binding(sessionId))
        assertEquals(
            session.directoryRef,
            FileScopePath(binding.workspaceId, binding.relativePath).toModelReference(),
        )

        storage.sessionPermissionConfigs.setForSession(
            sessionId,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            System.currentTimeMillis(),
        )
        container.toolPipeline.registry
            .all()
            .filter { it.name.value !in setOf("read", "write") }
            .forEach { descriptor ->
                storage.toolAvailability.set(
                    descriptor.origin.canonicalOf(),
                    descriptor.name.value,
                    ToolAvailabilityScope.SESSION,
                    sessionId,
                    ToolAvailabilityState.DISABLED,
                    System.currentTimeMillis(),
                )
            }
        chat.setMode(AgentMode.ACT)
        withTimeout(10_000) {
            while (storage.sessionRunControls.forSession(sessionId)?.mode != AgentMode.ACT) delay(20)
        }
        chat.setReasoning(ReasoningEffort.OFF)
        chat.setTurnBudgets(TurnBudgets(4, 4, 8_000, 512, 60_000))

        val relative =
            listOf(binding.relativePath.takeIf { it.isNotEmpty() }, TARGET_NAME)
                .filterNotNull()
                .joinToString("/")
        val targetRef = FileScopePath(binding.workspaceId, relative).toModelReference()
        val target =
            storage.workspaces
                .managedDirectory(binding.workspaceId)
                .resolve(relative)
                .toFile()
        val prompt =
            """
            Use only the write and read tools. /no_think
            1. Call write exactly once for $targetRef with overwrite=true and exact content: $TARGET_CONTENT
            2. After the write succeeds, call read for the same path exactly once.
            3. After the read succeeds, reply briefly with DONE.
            Do not ask questions and do not perform any other work.
            """.trimIndent()
        val receipt =
            chat
                .sendSubmission(ChatSubmission(sessionId, 0, UUID.randomUUID().toString(), prompt, emptyList()))
                .await()
        assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
        withTimeout(timeoutMs) {
            while (storage.turns
                    .listBySession(sessionId)
                    .singleOrNull()
                    ?.state !in TERMINAL_STATES
            ) {
                delay(100)
            }
        }

        val turn = storage.turns.listBySession(sessionId).single()
        val calls = storage.toolCalls.listByTurn(turn.id)
        val modelCalls = storage.modelCalls.listByTurn(turn.id)
        val messages = storage.messages.listBySession(sessionId)
        assertTrue(
            "P4 real-model turn failed: state=${turn.state} error=${turn.errorCode} " +
                "steps=${turn.stepCount} modelCalls=${modelCalls.map { it.state + ':' + (it.usage ?: "-") }} " +
                "toolCalls=${calls.map { it.name + ':' + it.state + ':' + it.argsJson.take(180) }}",
            turn.state == "COMPLETED",
        )
        assertEquals(1, calls.count { it.name == "write" })
        assertEquals(1, calls.count { it.name == "read" })
        assertTrue(calls.all { it.state == "COMPLETED" })
        assertTrue(modelCalls.size >= 2)
        assertTrue(modelCalls.all { storage.workspaces.requestBinding(it.id)?.workspaceId == binding.workspaceId })
        assertTrue(target.isFile)
        assertEquals(TARGET_CONTENT, target.readText())
        val artifact = storage.artifacts.listByTurn(turn.id).single { it.relativePath == targetRef }
        assertEquals(sha256(target), artifact.sha256)
        assertTrue(messages.any { it.role == "USER" })
        assertTrue(messages.any { it.role == "ASSISTANT" })

        val properties =
            Properties().apply {
                setProperty("sessionId", sessionId)
                setProperty("turnId", turn.id)
                setProperty("providerId", installed.providerId)
                setProperty("modelId", installed.modelId)
                setProperty("workspaceId", binding.workspaceId)
                setProperty("workspaceRelativePath", binding.relativePath)
                setProperty("workspaceRevision", binding.revision.toString())
                setProperty("targetRef", targetRef)
                setProperty("artifactId", artifact.id)
                setProperty("artifactSha", artifact.sha256)
                setProperty("toolCount", calls.size.toString())
                setProperty("modelCallCount", modelCalls.size.toString())
                setProperty("messageCount", messages.size.toString())
                setProperty("pid", Process.myPid().toString())
            }
        marker.outputStream().use { properties.store(it, "P4 first-success durable facts") }
        File(app.noBackupFilesDir, PID_FILE).writeText(Process.myPid().toString())
        evidence(app).writeText(
            "setup pid=${Process.myPid()} session=$sessionId turn=${turn.id} workspace=${binding.workspaceId} " +
                "tools=${calls.map { it.name + ':' + it.state }} artifact=${artifact.id}:${artifact.sha256}\n",
        )
        Process.killProcess(Process.myPid())
        error("Expected P4 setup process death")
    }

    @Suppress("LongMethod") // One restart pass compares the complete durable snapshot without mutating it.
    private suspend fun verifyAfterRestart() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val storage = container.storage
        val chat = container.chatService
        val properties = Properties().apply { marker(app).inputStream().use(::load) }
        val sessionId = requireNotNull(properties.getProperty("sessionId"))
        val turnId = requireNotNull(properties.getProperty("turnId"))
        val providerId = requireNotNull(properties.getProperty("providerId"))
        val modelId = requireNotNull(properties.getProperty("modelId"))
        val workspaceId = requireNotNull(properties.getProperty("workspaceId"))
        val workspaceRelative = requireNotNull(properties.getProperty("workspaceRelativePath"))
        val targetRef = requireNotNull(properties.getProperty("targetRef"))
        val expectedToolCount = requireNotNull(properties.getProperty("toolCount")).toInt()
        val expectedModelCount = requireNotNull(properties.getProperty("modelCallCount")).toInt()
        val expectedMessageCount = requireNotNull(properties.getProperty("messageCount")).toInt()
        val previousPid = requireNotNull(properties.getProperty("pid")).toInt()
        assertNotEquals(previousPid, Process.myPid())

        val session = storage.sessions.resolve(sessionId)
        assertEquals(providerId, session.providerId)
        assertEquals(modelId, session.modelId)
        val binding = requireNotNull(storage.workspaces.binding(sessionId))
        assertEquals(workspaceId, binding.workspaceId)
        assertEquals(workspaceRelative, binding.relativePath)
        assertEquals(requireNotNull(properties.getProperty("workspaceRevision")).toLong(), binding.revision)
        val turn = storage.turns.resolve(turnId)
        assertEquals("COMPLETED", turn.state)
        assertEquals(expectedToolCount, storage.toolCalls.listByTurn(turnId).size)
        assertEquals(expectedModelCount, storage.modelCalls.listByTurn(turnId).size)
        assertEquals(expectedMessageCount, storage.messages.listBySession(sessionId).size)
        assertEquals(1, storage.toolCalls.listByTurn(turnId).count { it.name == "write" })

        val targetPath = FileScopePath.fromModelReference(targetRef)
        val target =
            storage.workspaces
                .managedDirectory(workspaceId)
                .resolve(targetPath.relativePath)
                .toFile()
        assertEquals(TARGET_CONTENT, target.readText())
        val artifact = storage.artifacts.listByTurn(turnId).single { it.id == properties.getProperty("artifactId") }
        assertEquals(targetRef, artifact.relativePath)
        assertEquals(properties.getProperty("artifactSha"), artifact.sha256)
        assertEquals(artifact.sha256, sha256(target))

        chat.openSession(sessionId)
        withTimeout(10_000) {
            while (
                chat.screen.value.openSessionId != sessionId ||
                chat.screen.value.badge
                    ?.model != modelId ||
                chat.screen.value.messages.size < 2
            ) {
                delay(20)
            }
        }
        assertEquals(session.directoryRef, chat.screen.value.directoryRef)
        assertTrue(
            chat.screen.value.messages
                .any { it.role == "user" && it.content.contains(TARGET_CONTENT) },
        )
        assertTrue(
            chat.screen.value.messages
                .any { it.role == "assistant" && it.content.isNotBlank() },
        )
        assertTrue(
            chat.screen.value.toolTimeline
                .any { it.toolName == "write" },
        )
        assertTrue(
            chat.screen.value.toolTimeline
                .any { it.toolName == "read" },
        )
        assertTrue(chat.conversationArtifacts(sessionId).any { it.id == artifact.id })

        delay(2_000)
        assertEquals("restart must not create a second turn", 1, storage.turns.listBySession(sessionId).size)
        assertEquals(expectedToolCount, storage.toolCalls.listByTurn(turnId).size)
        assertEquals(expectedModelCount, storage.modelCalls.listByTurn(turnId).size)
        assertEquals(expectedMessageCount, storage.messages.listBySession(sessionId).size)
        assertEquals(TARGET_CONTENT, target.readText())
        evidence(app).appendText(
            "verify pid=${Process.myPid()} session=$sessionId turn=$turnId restoredModel=$modelId " +
                "workspace=$workspaceId tools=$expectedToolCount modelCalls=$expectedModelCount " +
                "messages=$expectedMessageCount " +
                "artifact=${artifact.id}:${artifact.sha256} duplicateSideEffect=false\n",
        )
    }

    private fun marker(app: HelixApplication) = File(app.noBackupFilesDir, MARKER_FILE)

    private fun evidence(app: HelixApplication): File =
        File(app.filesDir, "p4-first-success/evidence.txt").apply { parentFile?.mkdirs() }

    private fun sha256(file: File): String =
        file.inputStream().use { source ->
            MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        }

    private companion object {
        const val TARGET_NAME = "first-success.txt"
        const val TARGET_CONTENT = "HELIX_FIRST_SUCCESS_V1"
        const val MARKER_FILE = "p4-first-success.properties"
        const val PID_FILE = "recovery-device-pid"
        val TERMINAL_STATES = setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")
    }
}
