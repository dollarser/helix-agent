package com.helix.app.a2a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.A2aAgentId
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.a2a.A2aBinding
import com.helix.extensions.a2a.A2aEnabledSkill
import com.helix.extensions.a2a.A2aInterfaceSnapshot
import com.helix.extensions.a2a.A2aNeedsReviewException
import com.helix.extensions.a2a.A2aRemoteArtifact
import com.helix.extensions.a2a.A2aRemotePart
import com.helix.extensions.a2a.A2aRemoteTaskState
import com.helix.extensions.a2a.A2aTaskClient
import com.helix.extensions.a2a.A2aTaskStreamHandle
import com.helix.extensions.a2a.A2aTaskStreamListener
import com.helix.extensions.a2a.A2aTaskSubmission
import com.helix.extensions.a2a.A2aTaskUpdate
import com.helix.extensions.a2a.A2aTransportFailure
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class A2aTaskRunnerDeviceTest {
    @Test
    fun processRestartReconcilesSameTaskAndReusesVerifiedUntrustedArtifact() {
        val fixture = Fixture("recovery")
        val client = CompletingClient()
        var storage = fixture.openStorage()
        try {
            fixture.seed(storage)
            val first = fixture.runner(storage, client).execute(fixture.call(), SKILL)
            val firstRef = first.importedArtifactRef()
            assertEquals(1, client.sendCalls)
            assertEquals(1, storage.artifacts.listBySession(SESSION_ID).size)
            assertEquals("UNTRUSTED_A2A_CONTENT", first["trust"]?.jsonPrimitive?.content)
            assertTrue(first.toString().contains("attemptedLocalTool"))
            assertTrue(first.toString().contains("remoteUrlsRejected"))

            storage.close()
            storage = fixture.openStorage()
            val afterRestart = fixture.runner(storage, client).execute(fixture.call(), SKILL)
            assertEquals(1, client.sendCalls)
            assertEquals(firstRef, afterRestart.importedArtifactRef())
            assertEquals(1, storage.artifacts.listBySession(SESSION_ID).size)

            val artifact = storage.artifacts.resolve(firstRef)
            fixture.resolve(artifact.relativePath).writeText("tampered")
            assertThrows(IllegalArgumentException::class.java) {
                fixture.runner(storage, client).reconcile(TOOL_CALL_ID, SESSION_ID)
            }
            assertEquals(1, client.sendCalls)
            assertEquals(1, storage.artifacts.listBySession(SESSION_ID).size)
        } finally {
            storage.close()
            fixture.cleanup()
        }
    }

    @Test
    fun ambiguousDeliveryIsParkedForReviewAndNeverResent() {
        val fixture = Fixture("ambiguous")
        val client = AmbiguousClient()
        val storage = fixture.openStorage()
        try {
            fixture.seed(storage)
            val runner = fixture.runner(storage, client)
            assertThrows(A2aNeedsReviewException::class.java) { runner.execute(fixture.call(), SKILL) }
            val saved = requireNotNull(storage.a2aTasks.resolve(TOOL_CALL_ID))
            assertEquals("NEEDS_REVIEW", saved.state)
            assertEquals("UNKNOWN", saved.deliveryState)
            assertEquals(null, saved.taskId)

            assertThrows(A2aNeedsReviewException::class.java) { runner.execute(fixture.call(), SKILL) }
            assertEquals(1, client.sendCalls)
        } finally {
            storage.close()
            fixture.cleanup()
        }
    }

    private class Fixture(
        label: String,
    ) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val suffix = "$label-${System.nanoTime()}"
        private val databaseName = "a2a-task-$suffix.db"
        private val contentRoot = context.filesDir.resolve("a2a-task-content-$suffix")
        private val workspaceRoot = context.filesDir.resolve("a2a-task-workspace-$suffix")

        fun openStorage(): HelixStorage = HelixStorage.open(context, databaseName, contentRoot)

        fun seed(storage: HelixStorage) {
            storage.sessions.create(SESSION_ID, "A2A device recovery", null, null, 1L)
            storage.turns.start("turn-device", SESSION_ID, 2L)
            storage.toolCalls.append(
                TOOL_CALL_ID,
                "turn-device",
                TOOL_CALL_ID,
                "a2a.device.echo",
                "1",
                "{\"task\":\"device recovery\"}",
                "RUNNING",
            )
            A2aStorageBridge(storage).registerDisabled(AGENT_ID, "https://agent.example/card", null)
        }

        fun runner(
            storage: HelixStorage,
            client: A2aTaskClient,
        ): A2aTaskRunner {
            workspaceRoot.mkdirs()
            val workspace = WorkspaceArtifactStore(ScopeRootResolver { workspaceRoot.toPath() })
            workspace.ensureLayout(WORKSPACE_SCOPE)
            return A2aTaskRunner(
                storage = storage,
                workspace = workspace,
                workspaceScopeId = WORKSPACE_SCOPE,
                resolveWorkspaceFile = { path -> resolve(path.relativePath) },
                client = client,
                now = System::currentTimeMillis,
            )
        }

        fun call(): ExecutableToolCall =
            ExecutableToolCall(
                toolCallId = TOOL_CALL_ID,
                toolName = "a2a.device.echo",
                toolVersion = "1",
                args = buildJsonObject { put("task", "device recovery") },
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                deadline = Instant.now().plusSeconds(10),
                cancel = NoCancellation,
                sessionId = SESSION_ID,
                turnId = "turn-device",
            )

        fun resolve(relativePath: String) = workspaceRoot.resolve(relativePath)

        fun cleanup() {
            context.deleteDatabase(databaseName)
            contentRoot.deleteRecursively()
            workspaceRoot.deleteRecursively()
        }
    }

    private class CompletingClient : UnsupportedStreamingClient() {
        var sendCalls = 0
        var getCalls = 0

        override fun send(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            submission: A2aTaskSubmission,
        ): A2aTaskUpdate {
            sendCalls += 1
            return update(A2aRemoteTaskState.WORKING, 1, final = false)
        }

        override fun getTask(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            taskId: String,
        ): A2aTaskUpdate {
            getCalls += 1
            return update(A2aRemoteTaskState.COMPLETED, getCalls.toLong() + 1, final = true)
        }

        private fun update(
            state: A2aRemoteTaskState,
            sequence: Long,
            final: Boolean,
        ) = A2aTaskUpdate(
            taskId = "remote-task-1",
            contextId = "remote-context-1",
            state = state,
            parts =
                if (final) {
                    listOf(
                        A2aRemotePart.Text("remote result"),
                        A2aRemotePart.Data(
                            buildJsonObject {
                                put("attemptedLocalTool", buildJsonObject { put("name", "bash") })
                            },
                        ),
                    )
                } else {
                    emptyList()
                },
            artifacts =
                if (final) {
                    listOf(
                        A2aRemoteArtifact(
                            artifactId = "remote-artifact-1",
                            name = "result",
                            parts =
                                listOf(
                                    A2aRemotePart.Raw(
                                        Base64.getEncoder().encodeToString("verified bytes".toByteArray()),
                                        "result.txt",
                                        "text/plain",
                                    ),
                                    A2aRemotePart.Url("https://remote.example/result", "result.txt", "text/plain"),
                                ),
                        ),
                    )
                } else {
                    emptyList()
                },
            sequence = sequence,
            eventId = "event-$sequence",
            final = final,
        )
    }

    private class AmbiguousClient : UnsupportedStreamingClient() {
        var sendCalls = 0

        override fun send(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            submission: A2aTaskSubmission,
        ): A2aTaskUpdate {
            sendCalls += 1
            throw A2aTransportFailure("connection lost after write", requestMayHaveArrived = true)
        }
    }

    private abstract class UnsupportedStreamingClient : A2aTaskClient {
        override fun send(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            submission: A2aTaskSubmission,
        ): A2aTaskUpdate = error("send not implemented")

        override fun sendStreaming(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            submission: A2aTaskSubmission,
            listener: A2aTaskStreamListener,
        ): A2aTaskStreamHandle = error("stream not expected")

        override fun getTask(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            taskId: String,
        ): A2aTaskUpdate = error("getTask not expected")

        override fun cancelTask(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            taskId: String,
        ): A2aTaskUpdate = error("cancelTask not expected")

        override fun subscribeToTask(
            interfaceSnapshot: A2aInterfaceSnapshot,
            bearer: String?,
            taskId: String,
            lastEventId: String?,
            listener: A2aTaskStreamListener,
        ): A2aTaskStreamHandle = error("subscribe not expected")
    }

    private fun JsonObject.importedArtifactRef(): String =
        getValue("artifacts")
            .jsonArray
            .single()
            .jsonObject
            .getValue("imported")
            .jsonArray
            .single()
            .jsonObject
            .getValue("artifactRef")
            .jsonPrimitive.content

    private companion object {
        const val AGENT_ID = "device-agent"
        const val SESSION_ID = "a2a-device-session"
        const val TOOL_CALL_ID = "a2a-device-call"
        const val WORKSPACE_SCOPE = "workspace"
        val SKILL =
            A2aEnabledSkill(
                agentId = A2aAgentId(AGENT_ID),
                skillId = "echo",
                interfaceSnapshot =
                    A2aInterfaceSnapshot(
                        endpoint = NormalizedEndpoint.parse("https://agent.example/a2a"),
                        binding = A2aBinding.JSON_RPC,
                        protocolVersion = "1.0",
                        tenant = null,
                    ),
                cardHash = "a".repeat(64),
                skillHash = "b".repeat(64),
                inputModes = listOf("text/plain", "application/json"),
                outputModes = listOf("text/plain", "application/json"),
            )
    }
}
