package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.egress.EgressRuleService
import com.helix.app.permissions.ui.EgressRuleSection
import com.helix.core.storage.repository.StoredEgressRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** No real authorization writes: failed reads must be visible and retry must actually reload. */
class EgressRuleFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingErrorMessageDoesNotBecomeLoadingAndRetryRecovers() {
        val failed = AtomicBoolean(true)
        val service =
            EgressRuleService(
                { if (failed.get()) throw IOException() else emptyList() },
                { error("unexpected create") },
                { error("unexpected revoke") },
            )
        compose.setContent { MaterialTheme { EgressRuleSection(service) } }
        await("egress-load-error")
        compose.onNodeWithTag("egress-loading").assertDoesNotExist()
        compose.onNodeWithTag("egress-empty").assertDoesNotExist()
        failed.set(false)
        compose.onNodeWithTag("egress-retry").performClick()
        await("egress-empty")
        compose.onNodeWithTag("egress-load-error").assertDoesNotExist()
    }

    @Test fun committedCreateUsesSubmittedDraftAndDoesNotEraseNewEdits() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stored = AtomicReference<List<StoredEgressRule>>(emptyList())
        val service =
            EgressRuleService(
                { stored.get() },
                { rule ->
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    StoredEgressRule("own", rule).also { stored.set(listOf(it)) }
                },
                { error("unexpected revoke") },
            )
        try {
            compose.setContent { MaterialTheme { EgressRuleSection(service) } }
            compose.onNodeWithTag("egress-target-id").performTextInput("submitted")
            compose.onNodeWithTag("egress-origin").performTextInput("https://example.com")
            compose.onNodeWithTag("egress-create-button").performClick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("egress-target-id").performTextClearance()
            compose.onNodeWithTag("egress-target-id").performTextInput("next-draft")
            release.countDown()
            await("egress-rule-target")
            compose.onNodeWithTag("egress-rule-target").assertTextEquals("provider:submitted")
            compose.onNodeWithTag("egress-target-id").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("next-draft")),
            )
            assertEquals(1, stored.get().size)
        } finally {
            release.countDown()
        }
    }

    private fun await(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
}
