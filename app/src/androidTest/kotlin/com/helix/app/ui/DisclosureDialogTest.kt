package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-028 egress disclosure dialog fixture (doc 10 section 2.6; ADR-0005):
 * the dialog renders the auditable summary (provider, origin in display form
 * with the default port hidden, residence, data categories, scope) and
 * explicitly states that M2 offers NO permanent-allow option — asserted both
 * on the rendered text and on the [EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2]
 * constant the dialog renders from (a future flip cannot go unnoticed).
 *
 * HXA-069: the dialog is hosted by [LocalizedComposeActivity] (debug build type only). A
 * Material3 AlertDialog composes its content in the HOST activity's window — a
 * `CompositionLocalProvider(LocalContext provides ...)` override does NOT reach that window —
 * so the fixture gives it an activity that re-applies the pinned zh-CN language in
 * attachBaseContext. That keeps the assertion on the app's canonical Chinese-first copy
 * independent of the device locale on EVERY API level (API 33+ would inherit zh-CN from the
 * system per-app locale the runner pushes, but API 29 has no per-app locale).
 *
 * Note: the compose v2 API's `onNodeWithText` matches EXACT node text by
 * default; partial assertions pass `substring = true` explicitly.
 */
@RunWith(AndroidJUnit4::class)
class DisclosureDialogTest {
    private val summary =
        EgressDisclosure.EgressSummary(
            providerId = "p1",
            providerName = "示例 Provider",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            origin = "https://api.example.com:443",
            residence = ProviderResidence.PUBLIC_CLOUD,
            categories =
                listOf(
                    EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT,
                    EgressDisclosure.DataCategory.REGULAR,
                ),
            scope = EgressDisclosure.SCOPE_CURRENT_SESSION,
            contentTruncated = false,
        )

    // The composable is supplied before the rule launches [LocalizedComposeActivity] (field
    // initializers run before the rule's apply), so the activity's onCreate composes this dialog.
    @get:Rule
    val composeRule =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                DisclosureDialog(summary, onConfirm = {}, onDismiss = {})
            }
        }

    @Test
    fun dialogShowsSummaryAndNoPermanentAllowInM2() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("egress-disclosure-dialog").assertIsDisplayed()
        // displayOrigin hides the default :443 port (exact node text).
        composeRule.onNodeWithText("目的地：https://api.example.com").assertIsDisplayed()
        composeRule.onNodeWithText("数据驻留：公共云").assertIsDisplayed()
        composeRule.onNodeWithText("数据类别：文件正文、普通内容").assertIsDisplayed()
        composeRule.onNodeWithText("范围：当前会话").assertIsDisplayed()
        composeRule.onNodeWithText("Provider：示例 Provider", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("本版本不提供“永久允许”选项", substring = true).assertIsDisplayed()

        // The dialog renders from this constant; pin M2's value in the same test.
        assertFalse(EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2)
    }
}
