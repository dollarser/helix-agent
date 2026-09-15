package com.helix.app.provider

import com.helix.app.AppContainer
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals

internal object SubscriptionProviderContractCheck {
    suspend fun verify(
        container: AppContainer,
        providerId: String,
        model: String,
    ) {
        val events =
            container.providerService
                .modelProviderFor(providerId)
                .stream(
                    ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "hello"))),
                ).toList()
        assertEquals(
            listOf<ModelEvent>(
                ModelEvent.TextDelta("HELIX_OK"),
                ModelEvent.Usage(2, 1),
                ModelEvent.Completed("stop"),
            ),
            events,
        )

        // The production ChatService reads run control from the persisted "helix-ui" store, so
        // a prior test class (e.g. the budget-exhaustion boundary test) can leave maxTotalTokens=1
        // behind and this turn would never complete; pin the contract to a clean run control.
        val chat = container.chatService
        chat.setMode(AgentMode.CHAT)
        chat.setChatToolsEnabled(false)
        chat.setTurnBudgets(TurnBudgets(3, 2, 10000, 128, 10000))

        val sessionId = chat.createSession("subscription", providerId, model)
        chat.openSession(sessionId)
        await("session opens") { chat.screen.value.openSessionId == sessionId }
        chat.send("hello from chat")
        await("chat turn completes") {
            chat.screen.value.activeTurn
                ?.state == TurnState.COMPLETED
        }
        assertEquals(
            "HELIX_OK",
            chat.screen.value.messages
                .last()
                .content,
        )
        val turn =
            container.storage.turns
                .listBySession(sessionId)
                .single()
        assertEquals(TurnState.COMPLETED.name, turn.state)
        assertEquals(
            1,
            container.storage.modelCalls
                .listByTurn(turn.id)
                .size,
        )
        verifyDurableResult(container, sessionId, turn.id)
    }

    private fun verifyDurableResult(
        container: AppContainer,
        sessionId: String,
        turnId: String,
    ) {
        val call =
            container.storage.modelCalls
                .listByTurn(turnId)
                .single()
        val artifact = container.storage.artifacts.resolve("cli-result-${call.id}")
        assertEquals(sessionId, artifact.sessionId)
        val app =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<com.helix.app.HelixApplication>()
        val bytes = java.io.File(app.filesDir, "workspaces/app/${artifact.relativePath}").readBytes()
        assertEquals(
            artifact.sha256,
            com.helix.core.storage.content.FileContentStore
                .sha256Hex(bytes),
        )
        val binding = SubscriptionJobBindingStore(container.storage).resolve(call.id)
        val jobId = (binding.getValue("jobId") as kotlinx.serialization.json.JsonPrimitive).content
        val client =
            com.helix.runtime.cli.client.CliModelJobClient(
                com.helix.runtime.cli.client
                    .CliRuntimeSupervisor(app),
            )
        val record = (client.query(jobId) as com.helix.runtime.cli.client.CliModelJobClient.StateOutcome.Ok).record
        org.junit.Assert.assertNotNull(record.reconciledAtEpochMillis)
        assertEquals(record.outputSha256, artifact.sha256)
    }

    private fun await(
        label: String,
        condition: () -> Boolean,
    ) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out: $label")
    }
}
