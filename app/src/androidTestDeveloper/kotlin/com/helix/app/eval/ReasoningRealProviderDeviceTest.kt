package com.helix.app.eval

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.ui.ComposerReasoningMenu
import com.helix.core.model.ReasoningEffort
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit real-service opt-in; operates a standalone picker, never changes the user's session. */
class ReasoningRealProviderDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detectFromPickerUsingConfiguredModel() {
        val args = InstrumentationRegistry.getArguments()
        val optIn = args.getString("helixRealModel") ?: "false"
        require(optIn in setOf("true", "false"))
        org.junit.Assume.assumeTrue(optIn == "true")
        val provider = requireNotNull(args.getString("helixProvider"))
        val model = requireNotNull(args.getString("helixModel"))
        val service = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.providerService
        val complete = AtomicBoolean()
        val calls = AtomicInteger()
        var options = emptyList<ReasoningEffort>()
        var selected = ReasoningEffort.OFF
        compose.setContent {
            MaterialTheme {
                ComposerReasoningMenu(
                    selected,
                    true,
                    { selected = it },
                    efforts = emptyList(),
                    onDetect = {
                        calls.incrementAndGet()
                        try {
                            service.discoverReasoningOptions(provider, model).also {
                                options = it
                                println("REASONING_DETECTION_OPTIONS=$it")
                            }
                        } finally {
                            complete.set(true)
                        }
                    },
                )
            }
        }
        compose.runOnIdle { assertEquals(0, calls.get()) }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.waitUntil(180_000) { complete.get() }
        if (options.isEmpty()) {
            compose.onNodeWithTag("chat-reasoning-status").assertIsDisplayed()
            println("REASONING_DETECTION_NO_CONFIRMED_OPTIONS")
        } else {
            val choice = options.last()
            compose.onNodeWithTag("chat-reasoning-${choice.name.lowercase()}").performClick()
            compose.runOnIdle { assertEquals(choice, selected) }
        }
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun configuredModelAcceptsHighReasoning() =
        kotlinx.coroutines.runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val optIn = args.getString("helixRealModel") ?: "false"
            require(optIn in setOf("true", "false"))
            org.junit.Assume.assumeTrue(optIn == "true")
            val id = requireNotNull(args.getString("helixProvider"))
            val model = requireNotNull(args.getString("helixModel"))
            val service = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.providerService
            assertEquals(ReasoningEffort.HIGH, service.resolveReasoning(id, model, ReasoningEffort.HIGH))
            val events =
                kotlinx.coroutines.withTimeout(90_000) {
                    service
                        .modelProviderFor(id, model)
                        .stream(
                            com.helix.core.model.ModelRequest(
                                model,
                                listOf(
                                    com.helix.core.model.ModelMessage(
                                        com.helix.core.model.ModelRole.USER,
                                        "Compute 17 * 19. Reply only with the result.",
                                    ),
                                ),
                                reasoning = ReasoningEffort.HIGH,
                            ),
                        ).toList()
                }
            org.junit.Assert.assertTrue(events.none { it is com.helix.core.model.ModelEvent.Error })
            val text = events.filterIsInstance<com.helix.core.model.ModelEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("323", text.trim())
            println("REASONING_HIGH_GENERATION_PASSED")
        }
}
