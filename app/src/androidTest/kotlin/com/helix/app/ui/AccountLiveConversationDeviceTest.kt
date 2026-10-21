package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.ChatService
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit opt-in: real account/model, synthetic text only; never photographs the signed-in account. */
class AccountLiveConversationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One bounded real send, rebuild, session switch and durable uniqueness assertion.
    fun composerSendSurvivesRebuildAndSessionSwitchWithoutAnotherTurn() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val enabled = args.getString("helix.uiLiveModel")
            assumeTrue("Explicit real-model profile is absent", enabled != null)
            require(enabled == "true")
            val app = compose.activity.application as HelixApplication
            require(app.packageName.endsWith(".asrvalidation"))
            compose.resetDeterministicUiState()
            signedIn(app) {
                val c = compose.container()
                val chat = c.chatService
                c.providerService.refreshModelCatalog(BuiltInModel.PROVIDER_ID)
                val session = chat.createSession("Synthetic live UI", BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
                val neighbor =
                    chat.createSession(
                        "Synthetic UI neighbor",
                        BuiltInModel.PROVIDER_ID,
                        BuiltInModel.MODEL_ID,
                    )
                try {
                    chat.openSession(session)
                    chat.setMode(AgentMode.CHAT)
                    chat.setReasoning(ReasoningEffort.LOW)
                    chat.setChatToolsEnabled(false)
                    compose.waitUntil(10_000) {
                        chat.runControl.value.mode == AgentMode.CHAT && !chat.runControl.value.chatToolsEnabled
                    }
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                    compose.onNodeWithTag("chat-input").performTextReplacement(
                        "Reply with exactly HELIX_UI_ACCEPT_57. No punctuation, explanation or tool calls.",
                    )
                    compose.onNodeWithTag("chat-send").performClick()
                    compose.waitUntil(15_000) {
                        c.storage.turns
                            .listBySession(session)
                            .size == 1
                    }
                    compose.runOnUiThread { compose.activity.recreate() }
                    compose.waitForIdle()
                    chat.openSession(neighbor)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == neighbor }
                    assertTrue(
                        chat.screen.value.messages
                            .isEmpty(),
                    )
                    chat.openSession(session)
                    compose.waitUntil(120_000) {
                        hasCompletedAnswer(chat, c.storage, session)
                    }
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .single()
                    val answer =
                        chat.screen.value.messages
                            .single { it.role == "assistant" }
                    assertEquals("HELIX_UI_ACCEPT_57", answer.content.trim())
                    assertTrue(
                        c.storage.toolCalls
                            .listByTurn(turn.id)
                            .isEmpty(),
                    )
                    assertTrue(
                        c.storage.turns
                            .listBySession(neighbor)
                            .isEmpty(),
                    )
                    compose.onNodeWithTag("chat-message-toggle-${answer.id}").performScrollTo().assertIsDisplayed()
                    File(app.cacheDir, "live-conversation-ui-result.txt").writeText(
                        "visibleReply=HELIX_UI_ACCEPT_57\nturns=1\ntoolCalls=0\n" +
                            "activityRebuild=passed\nsessionSwitch=passed\n",
                    )
                } finally {
                    cleanupFixture(app, chat, c.storage, session, neighbor)
                }
            }
        }

    private fun hasCompletedAnswer(
        chat: ChatService,
        storage: HelixStorage,
        session: String,
    ): Boolean =
        storage.turns
            .listBySession(session)
            .singleOrNull()
            ?.state == "COMPLETED" &&
            chat.screen.value.openSessionId == session &&
            chat.screen.value.messages
                .any { it.role == "assistant" && it.content.trim() == "HELIX_UI_ACCEPT_57" }

    private suspend fun cleanupFixture(
        app: HelixApplication,
        chat: ChatService,
        storage: HelixStorage,
        session: String,
        neighbor: String,
    ) = withContext(NonCancellable) {
        try {
            recordFacts(app, chat, storage, session)
        } finally {
            settle(chat, storage, session)
            chat.closeSession()
            storage.deleteSessionPermanently(session)
            storage.deleteSessionPermanently(neighbor)
        }
    }

    private fun recordFacts(
        app: HelixApplication,
        chat: ChatService,
        storage: HelixStorage,
        session: String,
    ) {
        val facts =
            buildJsonObject {
                put("originalSessionVisible", chat.screen.value.openSessionId == session)
                put(
                    "turns",
                    buildJsonArray {
                        storage.turns.listBySession(session).forEach {
                            add(
                                buildJsonObject {
                                    put("state", it.state)
                                    put("errorCode", it.errorCode)
                                },
                            )
                        }
                    },
                )
                put(
                    "uiRoles",
                    buildJsonArray {
                        chat.screen.value.messages
                            .forEach { add(it.role) }
                    },
                )
                put(
                    "persistedExactAnswers",
                    storage.messages.listBySession(session).count {
                        it.role == "ASSISTANT" && it.kind == "TEXT" &&
                            storage.messages.readContent(it)?.trim() == "HELIX_UI_ACCEPT_57"
                    },
                )
            }
        File(app.cacheDir, "live-conversation-ui-facts.json").writeText(facts.toString())
    }

    private suspend fun signedIn(
        app: HelixApplication,
        action: suspend () -> Unit,
    ) {
        try {
            login(app)
            action()
        } finally {
            withContext(NonCancellable) { app.account.logout() }
        }
    }

    private suspend fun login(app: HelixApplication) {
        val bridge = InstrumentationRegistry.getArguments().getString("accountCredentialBridge")
        require(bridge == "http://127.0.0.1:19091/one-shot-credentials")
        val response =
            OkHttpWireClient(followRedirects = false, maxBodyBytes = 4096)
                .open(WireRequest("GET", bridge, emptyMap(), null))
        try {
            check(response.status == 200)
            val values = Json.parseToJsonElement(response.body.bytes().toString(Charsets.UTF_8)).jsonObject
            app.account.login(
                values.getValue("email").jsonPrimitive.content,
                values.getValue("password").jsonPrimitive.content,
            )
        } finally {
            response.body.close()
        }
    }

    private suspend fun settle(
        chat: ChatService,
        storage: HelixStorage,
        session: String,
    ) {
        storage.turns
            .listBySession(
                session,
            ).filter { !TurnState.valueOf(it.state).isTerminal }
            .forEach { chat.stop(it.id) }
        withTimeout(10_000) {
            while (storage.turns.listBySession(session).any { !TurnState.valueOf(it.state).isTerminal }) {
                delay(25)
            }
        }
    }
}
