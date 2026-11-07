package com.helix.app.infrastructure.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.Clock
import com.helix.core.model.citation.Citation
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.feature.conversation.application.ConversationHistoryReader
import com.helix.feature.conversation.application.ConversationHistoryRequest
import com.helix.feature.conversation.presentation.ChatScreenProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** No application container or providers: these App inspection/Expert owners use an isolated Room fixture. */
class ConversationWorkspaceDetailsDeviceTest {
    @Test fun citationReadsOnlyTheExactOriginalSessionAndContentRevision() =
        Fixture().use { fixture ->
            runBlocking {
                val message = fixture.storage.messages.append("message", "session", null, "USER", "TEXT", "A😀B")
                val revision = ContentRef.parse(requireNotNull(message.contentRef)).sha256
                val citation = Citation("session", "session", "message", revision, 1, 2)
                val details = fixture.details()
                assertEquals("😀", details.resolveCitation(citation)?.excerpt)
                assertEquals("Session", details.resolveCitation(citation)?.title)
                assertNull(details.resolveCitation(citation.copy(id = "other-session")))
                assertNull(details.resolveCitation(citation.copy(revision = "0".repeat(64))))
                assertEquals(
                    1,
                    fixture.storage.messages
                        .listBySession("session")
                        .size,
                )
                assertTrue(
                    fixture.storage.turns
                        .listBySession("session")
                        .isEmpty(),
                )
            }
        }

    @Test fun nonSessionAndMissingCitationTargetsCannotInspectUnrelatedContent() =
        Fixture().use { fixture ->
            runBlocking {
                val details = fixture.details()
                val citation = Citation("session", "session", "missing", "0".repeat(64), 0, 0)
                assertNull(details.resolveCitation(citation))
                assertNull(details.resolveCitation(citation.copy(messageId = null)))
                assertNull(details.resolveCitation(citation.copy(kind = "source")))
            }
        }

    @Test fun navigationChangeBeforeTheSelectionTransactionRejectsWithoutMutation() =
        Fixture().use { fixture ->
            runBlocking {
                var checks = 0
                val experts = fixture.experts { if (checks++ == 0) "session" else "other-session" }
                assertTrue(experts.saveExpert("expert", "Expert", "Original instruction").await())
                assertFalse(experts.selectExpert("session", "expert").await())
                assertNull(fixture.storage.sessionExperts.forSession("session"))
                assertNull(fixture.storage.sessionExperts.forSession("other-session"))
            }
        }

    @Test fun editingReusableExpertDoesNotReplaceTheAlreadySelectedSessionSnapshot() =
        Fixture().use { fixture ->
            runBlocking {
                val experts = fixture.experts { "session" }
                assertTrue(experts.saveExpert("expert", "Expert", "Original instruction").await())
                assertTrue(experts.selectExpert("session", "expert").await())
                assertTrue(experts.saveExpert("expert", "Expert", "Changed library instruction").await())
                assertEquals("Original instruction", experts.loadSessionExpert("session").await()?.instruction)
                assertTrue(experts.selectExpert("session", "expert").await())
                assertEquals("Changed library instruction", experts.loadSessionExpert("session").await()?.instruction)
                assertFalse(experts.selectExpert("other-session", "expert").await())
                assertTrue(experts.selectExpert("session", null).await())
                assertNull(experts.loadSessionExpert("session").await())
            }
        }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "conversation-details-${UUID.randomUUID()}"
        private val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        private val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2000)
            }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            storage.sessions.create("session", "Session", null, null, 1000)
            storage.sessions.create("other-session", "Other", null, null, 1000)
        }

        fun experts(current: () -> String?) = ConversationExperts(storage, clock, scope, current)

        fun details() =
            ConversationWorkspaceDetails(
                storage,
                unusedHistory,
                ChatScreenProjection({
                    ""
                }, { "" }, { "" }),
            ) { "session" }

        override fun close() {
            scope.cancel()
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    companion object {
        private val unusedHistory =
            object : ConversationHistoryReader {
                override fun positionForCall(
                    sessionId: String,
                    callId: String,
                ): Nothing = error("History is not part of this fixture")

                override fun read(request: ConversationHistoryRequest): Nothing =
                    error("History is not part of this fixture")

                override fun messagesForTurn(turnId: String): Nothing = error("History is not part of this fixture")

                override fun turn(turnId: String): Nothing = error("History is not part of this fixture")

                override fun retryTarget(sessionId: String?): Nothing = error("History is not part of this fixture")

                override fun selectedModel(sessionId: String): Nothing = error("History is not part of this fixture")

                override fun selectedModel(
                    providerId: String?,
                    modelId: String?,
                ): Nothing = error("History is not part of this fixture")
            }
    }
}
