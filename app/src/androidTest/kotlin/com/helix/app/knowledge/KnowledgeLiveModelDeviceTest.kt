package com.helix.app.knowledge

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.KnowledgeReferences
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Opt-in, synthetic-only real account conversation; two generation requests maximum. */
class KnowledgeLiveModelDeviceTest {
    @Test
    fun liveSyntheticConversationReferencesAndSave(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val flag = args.getString("helix.knowledgeLiveModel")
            org.junit.Assume.assumeTrue("Explicit real-model opt-in is absent", flag != null)
            require(flag == "true")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName.endsWith(".asrvalidation"))
            val providers = app.awaitAppContainer().providerService
            val factory =
                providers.javaClass
                    .getDeclaredField("factory")
                    .apply { isAccessible = true }
                    .get(providers)
            val field = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
            val original = field.get(factory) as WireClient
            require(original is com.helix.app.account.BuiltInAccountWire)
            try {
                login(app, args.getString("accountCredentialBridge"))
                // Match production: bind an invocation to the account after login.
                val observer = KnowledgeLiveWire(original.newInvocation())
                field.set(factory, observer)
                providers.builtInModels.refresh(true)
                val catalog = providers.builtInModels.current()
                File(app.cacheDir, "knowledge-live-preparation.json").writeText(
                    buildJsonObject {
                        put("loginPassed", app.account.signedIn.value)
                        put("catalogFailure", catalog.failure?.name ?: "none")
                        put("expectedModelListed", BuiltInModel.MODEL_ID in catalog.models)
                    }.toString(),
                )
                assertEquals("Built-in catalog fetch must succeed", null, catalog.failure)
                assertTrue("Expected model must be in gateway catalog", BuiltInModel.MODEL_ID in catalog.models)
                withTimeout(30_000) {
                    providers.rows.first { rows ->
                        rows.any { it.id == BuiltInModel.PROVIDER_ID && it.modelSelectable(BuiltInModel.MODEL_ID) }
                    }
                }
                verifyConversation(app, observer)
            } finally {
                field.set(factory, original)
                withContext(NonCancellable) { app.account.logout() }
            }
        }

    private suspend fun login(
        app: HelixApplication,
        bridge: String?,
    ) {
        require(bridge == "http://127.0.0.1:19091/one-shot-credentials")
        val response =
            OkHttpWireClient(followRedirects = false, maxBodyBytes = 4096)
                .open(WireRequest("GET", bridge, emptyMap(), null))
        try {
            check(response.status == 200) { "Credential bridge unavailable" }
            val values = Json.parseToJsonElement(response.body.bytes().toString(Charsets.UTF_8)).jsonObject
            app.account.login(
                values.getValue("email").jsonPrimitive.content,
                values.getValue("password").jsonPrimitive.content,
            )
        } finally {
            response.body.close()
        }
    }

    @Suppress("LongMethod") // One real send with immutable history and saved-note assertions, then owned cleanup.
    private suspend fun verifyConversation(app: HelixApplication, wire: KnowledgeLiveWire) {
        val c = app.awaitAppContainer()
        val chat = c.chatService
        val library = app.knowledge
        val marker = UUID.randomUUID().toString()
        val session =
            chat.createSession(
                "Synthetic knowledge acceptance",
                BuiltInModel.PROVIDER_ID,
                BuiltInModel.MODEL_ID,
            )
        var sourceIds = emptyList<String>()
        var savedId: String? = null
        try {
            listOf(
                "Synthetic Harbor budget is 137 tokens. $marker",
                "Synthetic Harbor owner is TEAM_SYNTH_42. No release date is given. $marker",
            ).forEach { text ->
                library
                    .capture(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        "synthetic fixture",
                    ).join()
            }
            val sources =
                withTimeout(10_000) {
                    library.items.first { items -> items.count { marker in it.rawText } == 2 }
                }.filter { marker in it.rawText }
            sourceIds = sources.map { it.id }
            val references = sourceIds.map { library.references.select("SOURCE", it, "FULL") }
            chat.openSession(session)
            chat.setMode(AgentMode.CHAT)
            withTimeout(10_000) {
                while (c.storage.sessionRunControls
                        .forSession(session)
                        ?.mode != AgentMode.CHAT
                ) {
                    delay(50)
                }
            }
            val request =
                ChatSubmission(
                    session,
                    0,
                    UUID.randomUUID().toString(),
                    "Give Harbor budget, owner and release date. Preserve literal values. State missing facts.",
                    knowledgeReferences = references,
                )
            assertTrue(chat.sendSubmission(request).await().outcome is ChatSubmissionOutcome.PendingConfirmation)
            val accepted = chat.confirmSubmission(request).await().outcome as ChatSubmissionOutcome.Accepted
            withTimeout(90_000) {
                while (!TurnState
                        .valueOf(
                            c.storage.turns
                                .resolve(accepted.turnId)
                                .state,
                        ).isTerminal
                ) {
                    delay(50)
                }
            }
            assertEquals(
                "COMPLETED",
                c.storage.turns
                    .resolve(accepted.turnId)
                    .state,
            )
            val messages = c.storage.messages.byTurn(accepted.turnId)
            val answer = messages.last { it.role == "ASSISTANT" && it.kind == "TEXT" }
            val body = requireNotNull(c.storage.messages.readContent(answer))
            assertTrue("Actual request must carry both reference bodies", wire.referencesSeen)
            assertTrue(body.contains("137") && body.contains("TEAM_SYNTH_42"))
            val user = messages.single { it.role == "USER" }
            val input = requireNotNull(c.storage.sessionInputs.forMessage(user.id))
            assertEquals(references, input.knowledgeReferences)
            for (id in sourceIds.toList()) {
                library.remove(id)
                sourceIds = sourceIds - id
            }
            assertEquals(
                references,
                c.storage.sessionInputs
                    .forMessage(user.id)
                    ?.knowledgeReferences,
            )
            assertTrue(chat.saveReplyAsNote(session, answer.id).await())
            val note =
                library.noteService.notes
                    .first()
                    .single { it.originMessageId == answer.id }
            savedId = note.id
            assertEquals("NOTE", note.kind)
            assertEquals("DRAFT", note.state)
            assertEquals(body, note.body)
            assertEquals(references, KnowledgeReferences.decode(note.originReferencesJson))
            assertEquals(
                1,
                c.storage.turns
                    .listBySession(session)
                    .size,
            )
            assertEquals(
                0,
                c.storage.toolCalls
                    .listByTurn(accepted.turnId)
                    .size,
            )
            File(app.cacheDir, "knowledge-live-facts.json").writeText(
                buildJsonObject {
                    put("realModel", true)
                    put("referenceBodiesInActualRequest", wire.referencesSeen)
                    put("answerValuesCorrect", true)
                    put("historySnapshotSurvivesSourceDeletion", true)
                    put("savedBodyAndReferencesExact", true)
                    put("generationRequests", wire.requests)
                }.toString(),
            )
            println("KNOWLEDGE_LIVE_ASSERTIONS_PASSED requests=${wire.requests}")
        } finally {
            c.storage.turns
                .listBySession(session)
                .forEach { chat.stopTurn(it.id) }
            chat.closeSession()
            savedId?.let { library.noteService.get(it)?.let { note -> library.noteService.management.remove(note) } }
            sourceIds.forEach { library.remove(it) }
            c.storage.sessions.archive(session, System.currentTimeMillis())
        }
    }
}

private class KnowledgeLiveWire(
    private val original: WireClient,
) : WireClient {
    var requests = 0
        private set
    var referencesSeen = false
        private set

    override suspend fun open(request: WireRequest): WireResponse {
        if (request.method == "POST" && request.url == BuiltInModel.GENERATION_URL) {
            if (++requests > 2) throw IOException("Synthetic acceptance request budget exhausted")
            val body = requireNotNull(request.body).toString(Charsets.UTF_8)
            referencesSeen = referencesSeen || (
                body.contains("137 tokens") && body.contains("TEAM_SYNTH_42") &&
                    body.contains("No release date is given")
            )
        }
        return original.open(request)
    }
}
