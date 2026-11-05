package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.plugin.PluginComponentFact
import com.helix.app.plugin.PluginReadinessDetails
import com.helix.app.plugin.PluginReadinessSnapshot
import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import org.junit.Rule
import org.junit.Test

class PluginReadinessDetailsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val state =
        PluginReadinessSnapshot(
            "package-a",
            4,
            true,
            true,
            listOf(
                PluginComponentFact(
                    "server-a",
                    PluginComponentKind.MCP,
                    PluginComponentState.NOT_CONNECTED,
                    true,
                    false,
                    "https://example.invalid",
                    "account-a",
                ),
            ),
            emptyList(),
        )

    @Test fun detailsShowStatusDirectlyAndDiagnosticsStayOnDemand() {
        compose.setContent { MaterialTheme { Column { PluginReadinessDetails(state) } } }
        compose.onNodeWithTag("plugin-readiness-package-a").assertDoesNotExist()
        compose.onNodeWithText("https://example.invalid").assertIsDisplayed()
        compose.onNodeWithText("server-a", substring = true).assertDoesNotExist()
        compose.onNodeWithText("account-a").assertDoesNotExist()
        compose.onNodeWithTag("plugin-technical-package-a").performClick()
        compose.onNodeWithText("server-a", substring = true).assertIsDisplayed()
        compose.onNodeWithText("account-a").assertDoesNotExist()
        compose.onNodeWithTag("plugin-technical-package-a").performClick()
        compose.onNodeWithText("server-a", substring = true).assertDoesNotExist()
    }

    @Test fun replacingPackageOrRevisionDoesNotReplayDiagnosticExpansion() {
        val facts = mutableStateOf(state)
        compose.setContent { MaterialTheme { Column { PluginReadinessDetails(facts.value) } } }
        compose.onNodeWithTag("plugin-technical-package-a").performClick()
        compose.onNodeWithText("server-a", substring = true).assertIsDisplayed()
        compose.runOnIdle { facts.value = state.copy(pluginId = "package-b") }
        compose.onNodeWithText("server-a", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("plugin-technical-package-b").performClick()
        compose.runOnIdle { facts.value = facts.value.copy(revision = 5) }
        compose.onNodeWithText("server-a", substring = true).assertDoesNotExist()
    }

    @Test fun contentReadyButInvocationDeniedKeepsItsRequestReasonVisible() {
        val facts =
            state.copy(
                components =
                    listOf(
                        state.components.single().copy(
                            kind = PluginComponentKind.SKILL,
                            state = PluginComponentState.AVAILABLE,
                            loaded = true,
                            requestAvailable = false,
                            displayName = "Example skill",
                        ),
                    ),
            )
        compose.setContent { MaterialTheme { Column { PluginReadinessDetails(facts) } } }
        val context =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
        compose
            .onNodeWithText(context.getString(com.helix.app.R.string.plugin_state_request_unavailable))
            .assertIsDisplayed()
        compose.onNodeWithText("Example skill", substring = true).assertIsDisplayed()
    }

    @Test fun selectorDoesNotExposeComponentDisclosureOrTechnicalIdentifiers() {
        val row =
            com.helix.app.plugin.PluginSessionRow(
                "package-a",
                "Example plugin",
                false,
                false,
                true,
                false,
                readiness = state,
                setupReason = com.helix.app.plugin.PluginSetupReason.CONNECTION,
            )
        compose.setContent {
            MaterialTheme {
                com.helix.app.connector
                    .PluginSessionChoice(row, false, {}, {})
            }
        }
        compose.onNodeWithText("https://example.invalid").assertDoesNotExist()
        compose.onNodeWithTag("plugin-readiness-package-a").assertDoesNotExist()
        compose.onNodeWithTag("plugin-technical-package-a").assertDoesNotExist()
        compose.onNodeWithText("server-a", substring = true).assertDoesNotExist()
        compose.onNodeWithText("account-a").assertDoesNotExist()
    }

    @Test fun completedConfigurationRefreshesFactsWithoutChangingPackageIdentity() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = app.appContainer.pluginService
        val record = service.list().first { it.native != null }
        val refresh = mutableStateOf(0)
        val reads =
            java.util.concurrent.atomic
                .AtomicInteger()
        compose.setContent {
            MaterialTheme {
                Column {
                    com.helix.app.plugin.PluginReadinessSection(record, service, refresh.value) {
                        reads.incrementAndGet()
                        state.copy(
                            pluginId = record.id,
                            components =
                                listOf(
                                    state.components.single().copy(
                                        target =
                                            if (refresh.value ==
                                                0
                                            ) {
                                                "https://old.invalid"
                                            } else {
                                                "https://current.invalid"
                                            },
                                    ),
                                ),
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText("https://old.invalid"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.runOnIdle { refresh.value++ }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText("https://current.invalid"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("https://old.invalid").assertDoesNotExist()
        org.junit.Assert.assertEquals(2, reads.get())
    }

    @Test fun failedReadStaysAnErrorUntilExplicitRetryLoadsCurrentFacts() {
        val app =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<com.helix.app.HelixApplication>()
        val service = app.appContainer.pluginService
        val record = service.list().first { it.native != null }
        val attempts =
            java.util.concurrent.atomic
                .AtomicInteger()
        compose.setContent {
            MaterialTheme {
                Column {
                    com.helix.app.plugin.PluginReadinessSection(record, service) {
                        if (attempts.incrementAndGet() == 1) error("Synthetic readiness read failure")
                        state.copy(pluginId = record.id)
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("plugin-readiness-retry-${record.id}"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("plugin-technical-${record.id}").assertDoesNotExist()
        compose.onNodeWithTag("plugin-readiness-retry-${record.id}").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("plugin-technical-${record.id}"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("https://example.invalid").assertIsDisplayed()
        org.junit.Assert.assertEquals(2, attempts.get())
    }
}
