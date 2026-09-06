package com.helix.app.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.TurnState
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, opt-in real-account smoke. Normal CI skips it and never consumes subscription quota. */
@RunWith(AndroidJUnit4::class)
class CodexSubscriptionProviderRealAccountDeviceTest {
    @Test fun realSubscriptionCompletesThroughHelixChat() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSubscription") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val providerId = SubscriptionProviderModule.CODEX_ID
        val probe = container.providerService.runConnectionTest(providerId)
        assertTrue("subscription probe failed safely: $probe", probe is ProbeOutcome.Ok)
        val row = container.providerService.rows.value.single { it.id == providerId }

        val sessionId = container.chatService.createSession("real subscription smoke", row.id, row.model)
        container.chatService.openSession(sessionId)
        await("session opens") { container.chatService.screen.value.openSessionId == sessionId }
        container.chatService.send(PROMPT)
        await("real model turn terminates", timeoutMillis = 120_000) {
            container.chatService.screen.value.activeTurn?.state?.isTerminal == true
        }

        val turn = container.storage.turns.listBySession(sessionId).single()
        assertEquals(TurnState.COMPLETED.name, turn.state)
        assertEquals(1, container.storage.modelCalls.listByTurn(turn.id).size)
        assertEquals("HELIX_OK", container.chatService.screen.value.messages.last().content.trim())
    }

    private fun await(label: String, timeoutMillis: Long = 4_000, condition: () -> Boolean) {
        repeat((timeoutMillis / 20).toInt()) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out: $label")
    }

    private companion object {
        const val PROMPT = "Reply exactly HELIX_OK and nothing else."
    }
}
