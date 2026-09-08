package com.helix.app.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.proot.ProotRecoveredOutput
import com.helix.app.provider.SubscriptionRecoveredOutput

@Composable
@Suppress("FunctionName")
internal fun ProotResultPanel(
    output: ProotRecoveredOutput,
    callId: String,
) {
    val headings =
        listOf(
            stringResource(R.string.proot_recovery_stdout),
            stringResource(R.string.proot_recovery_stderr),
            stringResource(R.string.proot_recovery_files),
        )
    val pages =
        remember(output, headings) {
            val text =
                buildString {
                    appendLine(headings[0])
                    appendLine(output.stdout)
                    appendLine(headings[1])
                    appendLine(output.stderr)
                    appendLine(headings[2])
                    output.files.forEach { appendLine("${it.path} (${it.size})\nSHA-256: ${it.sha256}") }
                }
            SubscriptionRecoveredOutput.fromText(text).pages
        }
    var page by remember(pages) { mutableIntStateOf(0) }
    Text(stringResource(R.string.proot_recovery_result_saved))
    if (output.truncated) Text(stringResource(R.string.proot_recovery_truncated))
    SelectionContainer {
        Text(pages[page], modifier = Modifier.testTag("proot-result-text-$callId"))
    }
    if (pages.size > 1) {
        Text(stringResource(R.string.subscription_recovery_page, page + 1, pages.size))
        TextButton(enabled = page > 0, onClick = { page-- }, modifier = Modifier.testTag("proot-previous-$callId")) {
            Text(stringResource(R.string.subscription_recovery_previous))
        }
        TextButton(
            enabled = page < pages.lastIndex,
            onClick = { page++ },
            modifier = Modifier.testTag("proot-next-$callId"),
        ) {
            Text(stringResource(R.string.subscription_recovery_next))
        }
    }
}
