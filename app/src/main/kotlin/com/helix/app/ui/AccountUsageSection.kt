package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.AccountRequestFailure
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountUsageClient
import com.helix.app.account.AccountUsageSummary
import com.helix.app.account.summary
import com.helix.provider.api.wire.HttpFailureDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun AccountUsageSection(
    account: AccountSession,
    onAccountFailure: (Exception) -> Unit,
    readUsage: suspend () -> AccountUsageSummary = { AccountUsageClient(account).read().summary() },
) {
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember(account, account.revision) { mutableStateOf(true) }
    var usage by remember(account, account.revision) { mutableStateOf<AccountUsageSummary?>(null) }
    var failure by remember(account, account.revision) { mutableStateOf<Int?>(null) }
    var details by remember(account, account.revision) { mutableStateOf<HttpFailureDetails?>(null) }
    var retryBlocked by remember(details) { mutableStateOf((details?.retryAfterMillis ?: 0) > 0) }
    LaunchedEffect(details) {
        details?.retryAfterMillis?.takeIf { it > 0 }?.let { delay(it) }
        retryBlocked = false
    }
    LaunchedEffect(refresh, account, account.revision) {
        if (retryBlocked) return@LaunchedEffect
        loading = true
        failure = null
        details = null
        try {
            usage = readUsage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AccountRequestFailure) {
            if (error.status in setOf(401, 403)) usage = null
            failure = accountFailureLabel(error)
            details = error.details
            if (!account.signedIn.value) onAccountFailure(error)
        } catch (error: Exception) {
            failure = accountFailureLabel(error)
            if (!account.signedIn.value) onAccountFailure(error)
        } finally {
            loading = false
        }
    }
    Column(Modifier.testTag("account-usage"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (loading) AccountLoadingStatus(R.string.account_usage_loading, "account-usage-loading")
        failure?.let { AccountFailureNotice(it, details) }
        if (usage != null) {
            AccountUsageContent(requireNotNull(usage))
        } else {
            AccountUsagePlaceholders()
        }
        OutlinedButton({ refresh++ }, enabled = !loading && !retryBlocked) {
            Text(stringResource(R.string.account_usage_refresh))
        }
    }
}
