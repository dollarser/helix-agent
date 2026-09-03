package com.helix.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.UUID

/**
 * HXA-056: share-draft UI device tests (roadmap §9A: "接入 ACTION_SEND/ACTION_SEND_MULTIPLE 的
 * 文字/图片草稿，所有分享输入先本地导入/预览且绝不自动发送"). The activity is launched with a
 * real share intent; the assertions prove the draft lands LOCALLY — the composer is pre-filled
 * (text) or the image is imported and staged (image) — while NO turn is ever sent
 * (ADR-0014 §5: preview first, then an explicit user send).
 */
@RunWith(AndroidJUnit4::class)
class ShareDraftUiDeviceTest {
    private typealias Rule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

    @Test
    fun shareTextIntentPreFillsTheComposerAndNeverSends() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shared = "分享草稿测试：请总结这段话 ${UUID.randomUUID()}"
        // Explicit component: an unqualified SEND intent resolves to another app (the test
        // would fail with "resolved to different process" before ever reaching MainActivity).
        val intent =
            Intent(Intent.ACTION_SEND, null, context, MainActivity::class.java).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shared)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        launchWithIntent(intent, "share") { rule ->
            dismissFirstLaunchNoticeIfNeeded(rule)
            // The draft session is open, the composer is pre-filled, and the provider-free
            // session offers the explicit bind affordance (no provider was auto-assigned).
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("chat-input").assertTextContains(shared)
            rule.waitUntil(
                15_000,
            ) { rule.onAllNodesWithTag("chat-unbound-provider").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("chat-unbound-provider").assertIsDisplayed()
            assertNoTurnWasSent(rule)
        }
    }

    @Test
    fun shareImageIntentImportsAndStagesNeverSends() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF00AA00.toInt())
        // Under files/workspaces/ so the FileProvider `files-path` mapping covers the source.
        val source = File(context.filesDir, "workspaces/share-${UUID.randomUUID()}.png")
        source.parentFile?.mkdirs()
        source.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val intent =
            Intent(Intent.ACTION_SEND, null, context, MainActivity::class.java).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        launchWithIntent(intent, "share") { rule ->
            dismissFirstLaunchNoticeIfNeeded(rule)
            // The image was imported through the REAL production pipeline and staged for
            // preview — the pending-attachments chip is visible…
            rule.waitUntil(
                20_000,
            ) { rule.onAllNodesWithTag("chat-pending-attachments").fetchSemanticsNodes().isNotEmpty() }
            // …the composer is NOT pre-filled (no text was shared)…
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty() }
            // A share of an image carries NO text: the composer stays empty, so the
            // placeholder (which only renders for an empty field) is what is displayed.
            // (assertTextEquals is not usable here: it checks Text AND EditableText, and an
            // empty field reports the placeholder for Text but "" for EditableText.)
            val inputText =
                rule.onNodeWithTag("chat-input").fetchSemanticsNode().config[SemanticsProperties.Text]
            assertTrue(
                "the image share must not prefill the composer, was: $inputText",
                (inputText as? List<*>).orEmpty().any { it.toString().contains("输入消息…") },
            )
            // …the session is provider-free (the user must explicitly bind before any send)…
            rule.waitUntil(
                15_000,
            ) { rule.onAllNodesWithTag("chat-unbound-provider").fetchSemanticsNodes().isNotEmpty() }
            // …and nothing was sent.
            assertNoTurnWasSent(rule)
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Runs [body] with a Compose test rule over an [ActivityScenarioRule] launched with
     * [intent]. The full JUnit rule lifecycle (activity launch, compose environment,
     * teardown) runs through [AndroidComposeTestRule.apply].
     */
    private fun launchWithIntent(
        intent: Intent,
        testName: String,
        body: (Rule) -> Unit,
    ) {
        val scenarioRule = ActivityScenarioRule<MainActivity>(intent)
        val rule: Rule =
            AndroidComposeTestRule(scenarioRule) { r ->
                var activity: MainActivity? = null
                r.scenario.onActivity { activity = it }
                checkNotNull(activity) { "activity was not set in the ActivityScenarioRule" }
            }
        val statement =
            object : Statement() {
                override fun evaluate() {
                    body(rule)
                }
            }
        rule
            .apply(statement, Description.createTestDescription(ShareDraftUiDeviceTest::class.java, testName))
            .evaluate()
    }

    private fun dismissFirstLaunchNoticeIfNeeded(rule: Rule) {
        rule.waitForIdle()
        if (rule.onAllNodesWithTag("first-launch-continue").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("first-launch-continue").performClick()
            rule.waitForIdle()
        }
    }

    private fun assertNoTurnWasSent(rule: Rule) {
        assertTrue(
            "no user message may have been sent",
            rule.onAllNodesWithTag("chat-message-user").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "no assistant message may have been sent",
            rule.onAllNodesWithTag("chat-message-assistant").fetchSemanticsNodes().isEmpty(),
        )
    }
}
