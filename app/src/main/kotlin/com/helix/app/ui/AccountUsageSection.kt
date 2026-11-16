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
) {
    var expanded by remember(account.revision) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var usage by remember(account.revision) { mutableStateOf<AccountUsageSummary?>(null) }
    var failure by remember(account.revision) { mutableStateOf<Int?>(null) }
    var details by remember(account.revision) { mutableStateOf<HttpFailureDetails?>(null) }
    var retryBlocked by remember(details) { mutableStateOf((details?.retryAfterMillis ?: 0) > 0) }
    LaunchedEffect(details) {
        details?.retryAfterMillis?.takeIf { it > 0 }?.let { delay(it) }
        retryBlocked = false
    }
    LaunchedEffect(expanded, refresh, account.revision) {
        if (!expanded || retryBlocked) return@LaunchedEffect
        loading = true
        usage = null
        failure = null
        details = null
        try {
            usage = AccountUsageClient(account).read().summary()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = accountFailureLabel(error)
            details = (error as? AccountRequestFailure)?.details
            if (!account.signedIn.value) onAccountFailure(error)
        } finally {
            loading = false
        }
    }
    OutlinedButton({ expanded = !expanded }, Modifier.testTag("account-usage-toggle")) {
        Text(stringResource(if (expanded) R.string.account_usage_hide else R.string.account_usage_title))
    }
    if (expanded) {
        Column(Modifier.testTag("account-usage"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) Text(stringResource(R.string.account_usage_loading))
            failure?.let { AccountFailureNotice(it, details) }
            usage?.let { AccountUsageContent(it) }
            OutlinedButton({
                refresh++
            }, enabled = !loading && !retryBlocked) { Text(stringResource(R.string.account_usage_refresh)) }
        }
    }
}
