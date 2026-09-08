package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DisclosureReadableUiTest {
    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                DisclosureDialog(
                    EgressDisclosure.EgressSummary(
                        "fixture",
                        "本地模型服务",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        "https://example.com:443",
                        ProviderResidence.PUBLIC_CLOUD,
                        listOf(EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT),
                        EgressDisclosure.SCOPE_CURRENT_SESSION,
                        false,
                        List(8) { index ->
                            EgressDisclosure.EgressAttachment(
                                "附件$index-" + "用于检查较长文件名称的完整展示".repeat(3),
                                1024,
                                "a".repeat(64),
                                R.string.nav_files,
                            )
                        },
                    ),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

    @Test fun longDisclosureScrollsAllDetailsAboveSeparateActions() {
        compose.onNodeWithText("附件7-", substring = true).performScrollTo().assertIsDisplayed()
        val note = compose.onNodeWithText("本次确认仅对本次发送生效", substring = true)
        note.performScrollTo().assertIsDisplayed()
        val noteBounds = note.fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithTag("egress-disclosure-details").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithTag("egress-confirm")
        confirm.assertIsDisplayed()
        assertTrue(noteBounds.top >= details.top && noteBounds.bottom <= details.bottom)
        assertTrue(noteBounds.bottom <= confirm.fetchSemanticsNode().boundsInRoot.top)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(compose.activity.cacheDir, "hxa147-disclosure.png").outputStream().use {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
        compose.onNodeWithText("Provider：本地模型服务", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("egress-dismiss").assertIsDisplayed()
    }
}
