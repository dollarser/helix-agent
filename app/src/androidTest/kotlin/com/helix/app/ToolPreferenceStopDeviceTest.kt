package com.helix.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Real ChatService.send/stop and model tool call, with a local HTTP fixture and no account. */
@RunWith(AndroidJUnit4::class)
class ToolPreferenceStopDeviceTest {
    @Test fun bundledJgitRejectsTheTrustAllPath() {
        val manager =
            org.eclipse.jgit.transport.http
                .NoCheckX509TrustManager()
        org.junit.Assert.assertThrows(java.security.cert.CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), "RSA")
        }
        org.junit.Assert.assertThrows(java.security.cert.CertificateException::class.java) {
            manager.checkClientTrusted(emptyArray(), "RSA")
        }
    }

    @Test fun userStopDuringAskSettlesTheRealTurnAndRejectsTheOldCard() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val chat = container.chatService
            val executions = AtomicInteger()
            registerEcho(container, executions)
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(container, server.port)
                val session = chat.createSession("HXA200 stop fixture", provider, "fixture-model-a")
                val previous = chat.runControl.value
                try {
                    chat.openSession(session)
                    chat.setMode(AgentMode.ACT)
                    val descriptor = container.toolPipeline.registry.resolve(ToolName("echo"), ToolVersion(1))
                    container.toolApprovalPreferenceService.set(
                        descriptor.origin.canonicalOf(),
                        "echo",
                        ToolApprovalPreferenceScope.SESSION,
                        session,
                        ToolApprovalPreference.ASK,
                        null,
                        System.currentTimeMillis(),
                    )
                    chat.send("Echo probe.")
                    await {
                        container.storage.turns.listBySession(session).any { turn ->
                            container.storage.toolCalls.listByTurn(turn.id).any {
                                container.storage.approvals.byToolCall(it.id) != null
                            }
                        }
                    }
                    stopAndRecord(app, container, session, executions)
                } finally {
                    chat.stop()
                    await { !chat.screen.value.isSending }
                    chat.closeSession()
                    chat.setMode(previous.mode)
                    container.providerService.delete(provider)
                }
            }
        }

    private fun stopAndRecord(
        app: HelixApplication,
        container: AppContainer,
        session: String,
        executions: AtomicInteger,
    ) {
        val chat = container.chatService
        val turn =
            container.storage.turns
                .listBySession(session)
                .single()
        val call =
            container.storage.toolCalls
                .listByTurn(turn.id)
                .single()
        val approval = requireNotNull(container.storage.approvals.byToolCall(call.id))
        chat.stop()
        await {
            container.storage.turns
                .resolve(turn.id)
                .state == "CANCELLED" &&
                !chat.screen.value.isSending
        }
        assertEquals(
            "CANCELLED",
            container.storage.toolCalls
                .resolve(call.id)
                .state,
        )
        assertEquals(0, executions.get())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            container.toolPipeline.broker.decide(approval.id, ApprovalDecision.APPROVED)
        }
        assertTrue(
            app
                .getSharedPreferences("hxa200-recovery-fixture", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "call",
                    call.id,
                ).putString("session", session)
                .putInt("pid", android.os.Process.myPid())
                .commit(),
        )
    }

    private fun registerEcho(
        container: AppContainer,
        executions: AtomicInteger,
    ) {
        val descriptor =
            ToolDescriptor(
                ToolName("echo"),
                ToolVersion(1),
                "Stop fixture",
                inputSchema = JsonObject(emptyMap()),
                outputSchema = JsonObject(emptyMap()),
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L0,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = com.helix.tools.framework.ToolOrigin.BuiltInOrigin,
            )
        container.toolPipeline.registry.register(descriptor)
        container.toolPipeline.implementations.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    return ToolExecutorResult.Completed(JsonObject(emptyMap()))
                }
            },
        )
    }

    private suspend fun createProvider(
        container: AppContainer,
        port: Int,
    ): String {
        val service = container.providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "HXA200 local fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        val probe = service.runConnectionTest(id)
        assertTrue("local fixture probe: $probe", probe is ProbeOutcome.Ok)
        val capability = service.runCapabilityTest(id)
        assertTrue("local capability probe: $capability", capability is ProbeOutcome.Ok)
        return id
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("production state must settle", condition())
    }
}
