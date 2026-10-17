package com.helix.feature.browser.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.R

internal enum class BrowserDataClear(
    val title: Int,
    val explanation: Int,
) {
    COOKIES(R.string.browser_clear_cookies, R.string.browser_clear_cookies_effect),
    CACHE(R.string.browser_clear_cache, R.string.browser_clear_cache_effect),
    HISTORY(R.string.browser_clear_history, R.string.browser_clear_history_effect),
}

@Composable
@Suppress("FunctionName")
internal fun BrowserDataClearDialog(
    action: BrowserDataClear?,
    controller: BrowserController,
    onDismiss: () -> Unit,
) {
    if (action == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.title)) },
        text = { Text(stringResource(action.explanation)) },
        confirmButton = {
            TextButton({
                when (action) {
                    BrowserDataClear.COOKIES -> controller.clearCookies()
                    BrowserDataClear.CACHE -> controller.clearCache()
                    BrowserDataClear.HISTORY -> controller.clearHistory()
                }
                onDismiss()
            }, Modifier.testTag("browser-clear-confirm")) { Text(stringResource(action.title)) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("browser-clear-cancel")) {
                Text(stringResource(R.string.browser_cancel))
            }
        },
    )
}
