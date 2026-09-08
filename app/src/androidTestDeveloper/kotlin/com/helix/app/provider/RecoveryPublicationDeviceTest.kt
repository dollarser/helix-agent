package com.helix.app.provider

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.runtime.cli.client.CliModelProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RecoveryPublicationDeviceTest {
    @Test fun alreadyOpenConversationRefreshesAfterRecoveryCommit() {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val storage = container.storage
        val chat = container.chatService
        await { Thread.getAllStackTraces().keys.none { it.name == "helix-recovery" && it.isAlive } }
        val id = "refresh-" + UUID.randomUUID().toString()
        storage.sessions.create(id, "Owned recovery publication fixture", null, null, 1)
        val turn = storage.turns.start(id, id, 2)
        storage.modelCalls.append(id, id, "fixture", "INTERRUPTED")
        SubscriptionJobBindingStore(storage).record(
            LocalModelCallContext(id, id),
            "job_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12),
            ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "fixture"))),
            CliModelProvider.CODEX,
        )
        try {
            chat.openSession(id)
            await {
                chat.screen.value.activeTurn
                    ?.id == id
            }
            assertTrue(
                chat.screen.value.subscriptionRecoveries
                    .isEmpty(),
            )
            storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 3, null)
            chat.onRecoveryCompleted()
            await {
                chat.screen.value.subscriptionRecoveries
                    .any { it.modelCallId == id }
            }
            assertTrue(!chat.screen.value.isSending)
        } finally {
            chat.closeSession()
            storage.deleteSessionPermanently(id)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
    }
}
