package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.agent.ModelClientObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Owned durable records only; no model, token or connector request. */
class MessageRunMetricsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun durableUnknownUsageAndReportedUsageOpenFromTheActualMessage(): Unit =
        runBlocking {
            val c = (compose.activity.application as HelixApplication).awaitAppContainer()
            val id = "metrics-${UUID.randomUUID()}"
            val message = "$id-message"
            withContext(Dispatchers.IO) {
                c.storage.sessions.create(id, "Synthetic run metrics", null, null, 1)
                c.storage.turns.start(id, id, 1)
                val call = c.storage.modelCalls.append("$id-call", id, "{}", "RUNNING")
                val observation = ModelClientObservation(2000, 100, 400, false).json()
                c.storage.modelCalls.update(
                    call,
                    "COMPLETED",
                    "{\"outputTokens\":20,\"clientObservation\":$observation}",
                    null,
                )
                val unknown = c.storage.modelCalls.append("$id-unknown", id, "{}", "RUNNING")
                c.storage.modelCalls.update(unknown, "CANCELLED", null, null)
                c.storage.messages.append(message, id, id, "ASSISTANT", "TEXT", "Synthetic metrics answer")
            }
            try {
                val metrics = c.conversationDetails.messageRunMetrics(message)
                assertEquals(2, metrics.requests.size)
                assertNull(metrics.requests.first().inputTokens)
                assertEquals(10.0, requireNotNull(metrics.requests.first().requestTokensPerSecond), 0.0)
                assertNull(metrics.requests.last().requestTokensPerSecond)
                c.chatService.openSession(id)
                compose.waitUntil(15_000) {
                    compose
                        .onAllNodes(
                            androidx.compose.ui.test
                                .hasText("Synthetic metrics answer"),
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("chat-message-toggle-$message").performClick()
                compose.onNodeWithTag("message-metrics-$message").assertIsDisplayed().performClick()
                compose.waitUntil(15_000) {
                    compose
                        .onAllNodes(
                            androidx.compose.ui.test
                                .hasTestTag("run-metrics"),
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("run-metrics").assertIsDisplayed()
            } finally {
                c.chatService.closeSession()
                withContext(Dispatchers.IO) { c.storage.deleteSessionPermanently(id) }
            }
        }
}
