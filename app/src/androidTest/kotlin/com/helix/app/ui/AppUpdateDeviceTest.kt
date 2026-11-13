package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.update.AppRelease
import com.helix.app.update.MODELSCOPE_PROJECT
import com.helix.app.update.UpdateCheck
import com.helix.app.update.UpdateCheckException
import com.helix.app.update.UpdateFailure
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppUpdateDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun checksAndOpensDownloadOnlyOnSeparateUserActions() {
        var checks = 0
        val downloads = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                UpdateCheckEntry(1, check = {
                    checks++
                    UpdateCheck.Available(release())
                }) {
                    downloads += it
                    true
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, checks)
            assertEquals(0, downloads.size)
        }
        compose.onNodeWithTag("about-check-update").performClick()
        compose.onNodeWithTag("update-download").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_preview_code)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, checks)
            assertEquals(0, downloads.size)
        }
        compose.onNodeWithTag("update-download").performClick()
        compose.runOnIdle { assertEquals(listOf(release().apkUrl), downloads) }
    }

    @Test fun offlineFailureCanRetryAndBrowserFailureRemainsVisible() {
        var checks = 0
        compose.setContent {
            MaterialTheme {
                UpdateCheckEntry(1, check = {
                    if (++checks == 1) throw UpdateCheckException(UpdateFailure.NETWORK)
                    UpdateCheck.Available(release())
                }) { false }
            }
        }
        compose.onNodeWithTag("about-check-update").performClick()
        compose.onNodeWithText(context.getString(R.string.update_network_failed)).assertIsDisplayed()
        compose.onNodeWithTag("update-retry").performClick()
        compose.onNodeWithTag("update-download").performClick()
        compose.onNodeWithText(context.getString(R.string.update_open_failed)).assertIsDisplayed()
        compose.onNodeWithTag("update-close").performClick()
        compose.onNodeWithTag("update-result").assertDoesNotExist()
    }

    @Test fun dismissedRequestCannotReopenDialogOrReplaceANewerResult() {
        val pending = CompletableDeferred<UpdateCheck>()
        var count = 0
        compose.setContent {
            MaterialTheme {
                UpdateCheckEntry(1, check = { if (++count == 1) pending.await() else UpdateCheck.Current }) {
                    error("No download expected")
                }
            }
        }
        compose.onNodeWithTag("about-check-update").performClick()
        compose.onNodeWithTag("update-close").performClick()
        compose.onNodeWithTag("about-check-update").performClick()
        compose.runOnIdle { pending.complete(UpdateCheck.Available(release())) }
        compose.onNodeWithText(context.getString(R.string.update_current)).assertIsDisplayed()
        compose.onNodeWithTag("update-download").assertDoesNotExist()
    }

    private fun release() =
        AppRelease(
            "0.0.12",
            1,
            "com.helix.agent.developer",
            listOf("arm64-v8a"),
            "$MODELSCOPE_PROJECT/resolve/master/helix.apk",
            "a".repeat(64),
            100,
        )
}
