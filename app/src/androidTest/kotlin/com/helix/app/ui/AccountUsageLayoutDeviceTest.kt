package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.account.AccountQuota
import com.helix.app.account.AccountUsageRecord
import com.helix.app.account.AccountUsageSummary
import com.helix.app.account.AccountUsageTotal
import com.helix.app.account.AccountUsageUnit
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class AccountUsageLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun content() {
        val quota =
            AccountQuota(
                "fixture-transcribe",
                "2026-11-16",
                "Asia/Shanghai",
                AccountUsageUnit.MINUTES,
                BigDecimal("30"),
                BigDecimal("0.08"),
                BigDecimal.ZERO,
                BigDecimal("29.919967"),
                Instant.parse("2026-11-16T16:00:00Z"),
            )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountUsageContent(
                        AccountUsageSummary(
                            listOf(quota),
                            "2026-11-16",
                            listOf(AccountUsageTotal("asr", 3)),
                            listOf(AccountUsageRecord("asr", 200)),
                        ),
                    )
                }
            }
        }
    }

    @Test fun usageDetailsShowOnlyCountedAmount() {
        content()
        compose.onNodeWithTag("usage-quota-fixture-transcribe").assertIsDisplayed()
        compose.onNodeWithTag("usage-details-content-fixture-transcribe").assertDoesNotExist()
        compose.onNodeWithTag("usage-details-fixture-transcribe").performClick()
        compose.onNodeWithText("0.08", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("29.919967", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("usage-details-fixture-transcribe").performScrollTo().performClick()
        compose.onNodeWithTag("usage-details-content-fixture-transcribe").assertDoesNotExist()
    }

    @Test fun internalFieldsAndRequestDiagnosticsAreNotShown() {
        content()
        compose.onNodeWithTag("usage-details-fixture-transcribe").performClick()
        compose.onNodeWithText("fixture-transcribe", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Asia/Shanghai", substring = true).assertDoesNotExist()
        compose.onNodeWithText("asr", substring = true).assertDoesNotExist()
        compose.onNodeWithText("200").assertDoesNotExist()
        compose.onNodeWithTag("usage-history-toggle").assertDoesNotExist()
        compose.onNodeWithTag("usage-history").assertDoesNotExist()
    }
}
