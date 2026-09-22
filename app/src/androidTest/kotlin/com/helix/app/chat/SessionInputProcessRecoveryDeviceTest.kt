package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.provider.ProviderDraft
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Seed/verify halves for host-driven normal MainActivity SIGKILL recovery journeys. */
class SessionInputProcessRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun seedPendingProcessRecovery() = seed(PENDING)

    @Test fun seedAppendedProcessRecovery() = seed(APPENDED)

    @Test fun seedHttpInFlightProcessRecovery() = seed(HTTP_IN_FLIGHT)

    @Test fun seedCancellingProcessRecovery() = seed(CANCELLING)

    @Test fun verifyPendingProcessRecovery() = verify(PENDING)

    @Test fun verifyAppendedProcessRecovery() = verify(APPENDED)

    @Test fun verifyHttpInFlightProcessRecovery() = verify(HTTP_IN_FLIGHT)

    @Test fun verifyCancellingProcessRecovery() = verify(CANCELLING)

    private fun seed(scenario: String) =
        runBlocking {
            compose.resetDeterministicUiState()
            val port = requireNotNull(InstrumentationRegistry.getArguments().getString(PORT_ARGUMENT)).toInt()
            require(port in 1..65535)
            val container = compose.container()
            val provider =
                container.providerService.create(
                    ProviderDraft(
                        templateId = null,
                        displayName = "Input process fixture",
                        protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        endpoint = NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model = MODEL,
                        headersJson = "{}",
                        credentialRequired = false,
                        cleartext = CleartextAuthorization("127.0.0.1", port),
                        templateNotes = emptyList(),
                    ),
                    apiKey = null,
                    cleartextConfirmed = true,
                )
            assertTrue(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
            val session = container.chatService.createSession(title(scenario), provider, MODEL)
            container.chatService.closeSession()
            fixtureFile().writeText(
                buildJsonObject {
                    put("scenario", scenario)
                    put("sessionId", session)
                    put("providerId", provider)
                    put("title", title(scenario))
                    put("serverPort", port)
                }.toString(),
            )
        }

    @Suppress("LongMethod") // One verification snapshots all four durable no-replay boundaries.
    private fun verify(expectedScenario: String) {
        val fixture = Json.parseToJsonElement(fixtureFile().readText()).jsonObject
        assertEquals(expectedScenario, fixture.getValue("scenario").jsonPrimitive.content)
        val session = fixture.getValue("sessionId").jsonPrimitive.content
        val storage = compose.container().storage
        val turns = storage.turns.listBySession(session)
        assertEquals(1, turns.size)
        val turn = turns.single()
        assertEquals(TurnState.INTERRUPTED.name, turn.state)

        val inputs =
            (storage.sessionInputs.listPending(session) + storage.sessionInputs.recentAppended(session))
                .distinctBy(SessionInputRecord::inputId)
                .sortedBy(SessionInputRecord::sequence)
        val expectedInputs = if (expectedScenario in setOf(PENDING, CANCELLING)) 2 else 1
        assertEquals(expectedInputs, inputs.size)
        val active = inputs.first()
        assertEquals(ACTIVE_TEXT, storage.sessionInputs.readText(active))
        assertEquals(SessionInputState.APPENDED, active.state)
        assertEquals(turn.id, active.consumedTurnId)
        assertNotNull(active.messageId)
        if (expectedScenario == APPENDED) {
            assertNull(active.requestModelCallId)
        } else {
            assertNotNull(active.requestModelCallId)
        }

        val queued = inputs.getOrNull(1)
        if (expectedScenario in setOf(PENDING, CANCELLING)) {
            assertEquals(QUEUED_TEXT, storage.sessionInputs.readText(requireNotNull(queued)))
            assertEquals(SessionInputState.NEEDS_ATTENTION, queued.state)
            assertEquals(
                if (expectedScenario ==
                    CANCELLING
                ) {
                    "USER_STOP"
                } else {
                    "PROCESS_INTERRUPTED"
                },
                queued.blockedReason,
            )
            assertNull(queued.consumedTurnId)
            assertNull(queued.messageId)
            assertNull(queued.requestModelCallId)
        } else {
            assertNull(queued)
        }

        val messages = storage.messages.listBySession(session)
        val userMessages = messages.filter { it.role == "USER" }
        assertEquals(1, userMessages.size)
        assertEquals(ACTIVE_TEXT, storage.messages.readContent(userMessages.single()))
        assertFalse(messages.any { storage.messages.readContent(it) == QUEUED_TEXT })
        val calls = storage.modelCalls.listByTurn(turn.id)
        assertEquals(1, calls.size)
        assertEquals("INTERRUPTED", calls.single().state)

        val beforeInputs = inputs
        val beforeTurns = turns
        val beforeMessages = messages
        val beforeCalls = calls
        val repeated = RecoveryCoordinatorApp(storage, SystemClock()).recover()
        assertTrue(repeated.interruptedTurns.isEmpty())
        assertEquals(
            beforeInputs,
            (
                storage.sessionInputs.listPending(session) +
                    storage.sessionInputs.recentAppended(session)
            ).distinctBy(SessionInputRecord::inputId)
                .sortedBy(SessionInputRecord::sequence),
        )
        assertEquals(beforeTurns, storage.turns.listBySession(session))
        assertEquals(beforeMessages, storage.messages.listBySession(session))
        assertEquals(beforeCalls, storage.modelCalls.listByTurn(turn.id))

        evidenceFile().writeText(
            buildJsonObject {
                put("scenario", expectedScenario)
                put("sessionId", session)
                put("turnId", turn.id)
                put("turnState", turn.state)
                put("messageCount", messages.size)
                put("modelCallCount", calls.size)
                put("secondRecoveryInterrupted", repeated.interruptedTurns.size)
                put(
                    "inputs",
                    buildJsonArray {
                        inputs.forEach { input ->
                            add(
                                buildJsonObject {
                                    put("inputId", input.inputId)
                                    put("state", input.state.name)
                                    put("blockedReason", input.blockedReason)
                                    put("consumedTurnId", input.consumedTurnId)
                                    put("messageId", input.messageId)
                                    put("requestModelCallId", input.requestModelCallId)
                                },
                            )
                        }
                    },
                )
            }.toString(),
        )
    }

    private fun fixtureFile(): File = File(compose.activity.filesDir, FIXTURE_FILE)

    private fun evidenceFile(): File = File(compose.activity.filesDir, EVIDENCE_FILE)

    private fun title(scenario: String): String = "INPUT-PROCESS-${scenario.uppercase().replace('_', '-')}"

    private companion object {
        const val PORT_ARGUMENT = "inputProcessPort"
        const val FIXTURE_FILE = "session-input-process-fixture.json"
        const val EVIDENCE_FILE = "session-input-process-verified.json"
        const val MODEL = "fixture-model-a"
        const val ACTIVE_TEXT = "ACTIVE-PROCESS-INPUT"
        const val QUEUED_TEXT = "QUEUED-PROCESS-INPUT"
        const val PENDING = "pending"
        const val APPENDED = "appended"
        const val HTTP_IN_FLIGHT = "http_in_flight"
        const val CANCELLING = "cancelling"
    }
}
