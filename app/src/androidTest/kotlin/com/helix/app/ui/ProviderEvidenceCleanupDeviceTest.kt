package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.privacy.ProviderEvidenceCleanup
import com.helix.app.privacy.ProviderEvidenceCleanup.Status
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderEvidenceCleanupDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cleanupIsExplicitAndUnconfirmedReplyNeverShowsZeroDeletedAsFact() {
        val requests = mutableListOf<String?>()
        compose.setContent {
            MaterialTheme {
                ProviderEvidenceCleanupSection { after ->
                    requests += after
                    when (requests.size) {
                        1 -> ProviderEvidenceCleanup(Status.MORE_AVAILABLE, nextAfter = "a".repeat(64))
                        2 -> error("fixture_private_error")
                        else -> ProviderEvidenceCleanup(Status.COMPLETE)
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String?>(), requests) }
        compose.onNodeWithTag("replay-cleanup-run").performClick()
        compose.onNodeWithTag("replay-cleanup-counts").assertExists()
        compose.onNodeWithTag("replay-cleanup-run").performClick()
        compose.onNodeWithTag("replay-cleanup-counts").assertDoesNotExist()
        compose.onNodeWithText("fixture_private_error").assertDoesNotExist()
        compose.onNodeWithTag("replay-cleanup-run").performClick()
        compose.runOnIdle { assertEquals(listOf(null, "a".repeat(64), null), requests) }
    }

    @Test fun anOutstandingOperationCannotBeSubmittedAgain() {
        val result = CompletableDeferred<ProviderEvidenceCleanup>()
        var calls = 0
        compose.setContent {
            MaterialTheme {
                ProviderEvidenceCleanupSection {
                    calls++
                    result.await()
                }
            }
        }
        compose.onNodeWithTag("replay-cleanup-run").performClick()
        compose.onNodeWithTag("replay-cleanup-run").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, calls)
            result.complete(ProviderEvidenceCleanup(Status.BUSY))
        }
        compose.onNodeWithTag("replay-cleanup-run").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
