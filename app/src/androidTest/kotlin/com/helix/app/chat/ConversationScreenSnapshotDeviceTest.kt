package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.chat.RoomRecoveryObservationStore
import com.helix.app.infrastructure.conversation.RoomConversationHistoryReader
import com.helix.app.infrastructure.conversation.RoomConversationScreenReader
import com.helix.app.infrastructure.conversation.RoomConversationWorkspace
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationScreenRequest
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.provider.api.CredentialLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ConversationScreenSnapshotDeviceTest {
    @Test fun durableFactsAreDetachedAndMissingIsNotAReadFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "screen-snapshot-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, "screen-snapshot-${UUID.randomUUID()}")
        val storage = HelixStorage.open(context, name, File(root, "content"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val providers =
            ProviderService(
                storage,
                ProviderFactory(
                    CredentialLookup { error("Display tests must not read credentials") },
                    ScriptedSseWire().wireClient,
                    { error("Display tests must not request images") },
                ),
                ProviderTestStatusStore(InMemoryLineStore()),
                idGenerator = { UUID.randomUUID().toString() },
                scope = scope,
            )
        try {
            storage.sessions.create("s", "Original", null, null, 1)
            storage.turns.start("turn", "s", 2)
            storage.messages.append("m1", "s", "turn", "USER", "TEXT", "Before")
            val history =
                RoomConversationHistoryReader(storage, providers, { _, _ -> "label" }, { 0 })
            val reader =
                RoomConversationScreenReader(
                    storage,
                    history,
                    providers,
                    { RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT) },
                    RoomConversationWorkspace(storage) { it },
                    RoomRecoveryObservationStore(storage),
                )
            val state = ChatScreenState(emptyList(), "s", null, emptyList(), emptyList(), null, null, null, null)
            val request = ConversationScreenRequest.capture("s", state, 1)
            val before = reader.read(request)
            storage.sessions.updateDetails("s", "Later", null)
            storage.messages.append("m2", "s", "turn", "ASSISTANT", "TEXT", "After")
            val after = reader.read(request)
            assertEquals("Original", before.session?.title)
            assertEquals(listOf("m1"), before.history.messages.map { it.presentation.id })
            assertEquals("Later", after.session?.title)
            assertEquals(listOf("m1", "m2"), after.history.messages.map { it.presentation.id })
            assertEquals("turn", after.activeTurn?.id)
            val missing = reader.read(ConversationScreenRequest.capture("absent", state, 2))
            assertEquals(true, missing.sessionMissing)
            assertEquals(null, missing.session)
            storage.close()
            // A failed database read propagates; callers must keep their selected conversation.
            assertTrue(runCatching { reader.read(request) }.isFailure)
        } finally {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
