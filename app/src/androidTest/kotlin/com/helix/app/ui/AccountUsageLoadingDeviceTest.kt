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
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.account.AccountQuota
import com.helix.app.account.AccountUsageSummary
import com.helix.app.account.AccountUsageUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

class AccountUsageLoadingDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val fixture = NoticeUiFixture()
    private val usage =
        AccountUsageSummary(
            listOf(
                AccountQuota(
                    "loading-fixture",
                    "month",
                    "Asia/Shanghai",
                    AccountUsageUnit.TOKENS,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal(9),
                ),
            ),
            "today",
            emptyList(),
            emptyList(),
        )

    private fun content(read: suspend () -> AccountUsageSummary) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountUsageSection(fixture.account, {}, read)
                }
            }
        }
    }

    @Test fun initialNetworkWaitShowsQuotaStructureAndThenRealValues() {
        val response = CompletableDeferred<AccountUsageSummary>()
        content { response.await() }
        compose.onNodeWithTag("account-usage-placeholders").assertExists()
        compose.onNodeWithTag("account-usage-loading").assertIsDisplayed()
        compose.onNodeWithTag("usage-quota-loading-fixture").assertDoesNotExist()
        compose.runOnIdle { response.complete(usage) }
        compose.onNodeWithTag("usage-quota-loading-fixture").assertIsDisplayed()
        compose.onNodeWithTag("account-usage-placeholders").assertDoesNotExist()
    }

    @Test fun refreshKeepsExistingQuotaVisibleAndRetainsItOnFailure() {
        var calls = 0
        val refresh = CompletableDeferred<AccountUsageSummary>()
        content { if (calls++ == 0) usage else refresh.await() }
        compose.onNodeWithTag("usage-quota-loading-fixture").assertIsDisplayed()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.account_usage_refresh)
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("usage-quota-loading-fixture").assertExists()
        compose.onNodeWithTag("account-usage-loading").assertExists()
        compose.runOnIdle { refresh.completeExceptionally(IOException("offline")) }
        compose.onNodeWithTag("usage-quota-loading-fixture").assertExists()
        compose.onNodeWithTag("account-usage-loading").assertDoesNotExist()
    }
}
