package com.helix.app.knowledge

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Opt-in only: the actual conversation path replaces the retired parallel Q&A runtime. */
class KnowledgeLiveModelDeviceTest {
    @Test
    @Suppress("LongMethod") // One bounded real conversation, explicit save and owned synthetic cleanup.
    fun liveSyntheticConversationReferencesAndSave(): Unit =
        runBlocking {
            val optIn = InstrumentationRegistry.getArguments().getString("helix.knowledgeLiveModel")
            org.junit.Assume.assumeTrue("Explicit real-model opt-in is absent", optIn != null)
            require(optIn == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".asrvalidation"))
            val app = context.applicationContext as HelixApplication
            val container = app.awaitAppContainer()
            val providers = container.providerService
            val chat = container.chatService
            val library = app.knowledge
            providers.refreshModelCatalog(BuiltInModel.PROVIDER_ID)
            withTimeout(30_000) {
                providers.rows.first { rows ->
                    rows.any {
                        it.id == BuiltInModel.PROVIDER_ID && it.modelSelectable(BuiltInModel.MODEL_ID)
                    }
                }
            }
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
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text),
                            "synthetic fixture",
                        ).join()
                }
                val sources = library.items.first().filter { marker in it.rawText }
                sourceIds = sources.map { it.id }
                assertEquals(2, sourceIds.size)
                val references = sourceIds.map { library.references.select("SOURCE", it, "FULL") }
                chat.openSession(session)
                chat.setMode(AgentMode.CHAT)
                withTimeout(10_000) {
                    while (container.storage.sessionRunControls
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
                        "What are Harbor budget, owner and release date? " +
                            "Preserve literal values and state missing information.",
                        knowledgeReferences = references,
                    )
                assertTrue(chat.sendSubmission(request).await().outcome is ChatSubmissionOutcome.PendingConfirmation)
                val confirmed = chat.confirmSubmission(request).await().outcome as ChatSubmissionOutcome.Accepted
                withTimeout(90_000) {
                    while (!TurnState
                            .valueOf(
                                container.storage.turns
                                    .resolve(confirmed.turnId)
                                    .state,
                            ).isTerminal
                    ) {
                        delay(50)
                    }
                }
                val answer =
                    container.storage.messages.byTurn(confirmed.turnId).last {
                        it.role == "ASSISTANT" && it.kind == "TEXT"
                    }
                val body = requireNotNull(container.storage.messages.readContent(answer))
                assertTrue(body.contains("137"))
                assertTrue(body.contains("TEAM_SYNTH_42"))
                assertTrue(chat.saveReplyAsNote(session, answer.id).await())
                val note =
                    library.noteService.notes
                        .first()
                        .single { it.originMessageId == answer.id }
                savedId = note.id
                assertEquals("NOTE", note.kind)
                assertEquals("DRAFT", note.state)
                assertEquals(body, note.body)
                val savedReferences =
                    com.helix.core.storage.repository.KnowledgeReferences
                        .decode(note.originReferencesJson)
                assertEquals(references, savedReferences)
            } finally {
                container.storage.turns
                    .listBySession(session)
                    .forEach { chat.stopTurn(it.id) }
                chat.closeSession()
                savedId?.let { id ->
                    library.noteService.get(id)?.let { note -> library.noteService.management.remove(note) }
                }
                sourceIds.forEach { library.remove(it) }
                container.storage.sessions.archive(session, System.currentTimeMillis())
            }
        }
}
