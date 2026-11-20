package com.helix.app.ui

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.MessageRunMetrics
import com.helix.core.model.citation.Citation
import com.helix.core.model.citation.CitationContent
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic suspended local reads; no network, model or persistent user data. */
class InspectionDialogLoadingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val citation = Citation("session", "fixture", "message", "a".repeat(64), 0, 4)

    @Test fun citationWaitsForResolvedTitleAndContent() {
        val read = CompletableDeferred<CitationContent?>()
        compose.setContent { MaterialTheme { CitationPreviewDialog(citation, { read.await() }, {}) } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        read.complete(CitationContent("Resolved title", "Body"))
        awaitDialog()
        compose.onNodeWithTag("citation-preview").assertIsDisplayed()
    }

    @Test fun citationFailureIsVisibleRatherThanEmptyOrLoadingForever() {
        val read = CompletableDeferred<CitationContent?>()
        compose.setContent { MaterialTheme { CitationPreviewDialog(citation, { read.await() }, {}) } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        read.completeExceptionally(IOException("Synthetic local read failure"))
        awaitDialog()
        compose.onNodeWithTag("citation-preview").assertIsDisplayed()
    }

    @Test fun leavingBeforeCitationReadCompletesDoesNotOpenLateDialog() {
        val read = CompletableDeferred<CitationContent?>()
        val visible = mutableStateOf(true)
        val cancelled = AtomicBoolean()
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    CitationPreviewDialog(citation, {
                        try {
                            read.await()
                        } finally {
                            cancelled.set(true)
                        }
                    }, {})
                }
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5000) { cancelled.get() }
        read.complete(CitationContent("Late", "Ignored"))
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun metricsWaitsForLocalReadAndPreservesEmptyMetrics() {
        val read = CompletableDeferred<MessageRunMetrics>()
        compose.setContent { MaterialTheme { MessageRunMetricsDialog("fixture", { read.await() }, {}) } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        read.complete(MessageRunMetrics(emptyList(), 0))
        awaitDialog()
        compose.onNodeWithTag("run-metrics").assertIsDisplayed()
    }

    @Test fun metricsFailureStillOpensDismissibleError() {
        val read = CompletableDeferred<MessageRunMetrics>()
        compose.setContent { MaterialTheme { MessageRunMetricsDialog("fixture", { read.await() }, {}) } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        read.completeExceptionally(IOException("Synthetic local read failure"))
        awaitDialog()
        compose.onNodeWithTag("run-metrics").assertDoesNotExist()
    }

    @Test fun resultRetryKeepsDialogAndDisablesActionsUntilReadSettles() {
        val first = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val collected = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                TaskResultDialog({
                    if (attempts.incrementAndGet() == 1) {
                        first.await()
                        throw IOException("Synthetic local read failure")
                    }
                    retry.await()
                    emptyList()
                }, { collected.incrementAndGet() }, "fixture", onDismiss = {})
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        first.complete(Unit)
        awaitDialog()
        compose.onNodeWithTag("task-result-retry").performClick()
        compose.waitUntil(5000) { attempts.get() == 2 }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithTag("task-result-retry").assertIsNotEnabled()
        compose.onNodeWithTag("task-collect-fixture").assertIsNotEnabled()
        retry.complete(Unit)
        compose.waitUntil(5000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("task-result-retry"),
                ).fetchSemanticsNodes()
                .isEmpty()
        }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithTag("task-collect-fixture").assertIsEnabled().performClick()
        assertEquals(1, collected.get())
    }

    private fun awaitDialog() {
        compose.waitUntil(5000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().size == 1 }
    }

    @Test fun pendingReadsCanBeCancelledWithSystemBack() {
        val gate = CompletableDeferred<Unit>()
        val selected = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                val dismiss = {
                    selected.value++
                    Unit
                }
                when (selected.value) {
                    0 -> {
                        CitationPreviewDialog(citation, {
                            gate.await()
                            null
                        }, dismiss)
                    }

                    1 -> {
                        MessageRunMetricsDialog("fixture", {
                            gate.await()
                            MessageRunMetrics(emptyList(), 0)
                        }, dismiss)
                    }

                    2 -> {
                        TaskResultDialog({
                            gate.await()
                            emptyList()
                        }, {}, "fixture", onDismiss = dismiss)
                    }

                    else -> {
                        Text("Original page")
                    }
                }
            }
        }
        repeat(3) { index ->
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) { selected.value == index + 1 }
        }
        gate.complete(Unit)
        compose.onNodeWithText("Original page").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
}
