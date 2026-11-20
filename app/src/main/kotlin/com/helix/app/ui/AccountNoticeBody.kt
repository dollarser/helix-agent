package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.helix.app.R

/** Link spans retain native accessible link semantics; the copy menu does not navigate. */
@Composable
@Suppress("FunctionName")
internal fun AccountNoticeBody(
    body: String,
    openLink: ((String) -> Unit)? = null,
    copyText: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val links = remember(body) { accountNoticeLinks(body) }
    var expanded by remember(body) { mutableStateOf(false) }
    var failed by remember(body) { mutableStateOf(false) }
    var copied by remember(body) { mutableStateOf(false) }
    val copy: (String) -> Unit = {
        if (copyText != null) copyText(it) else clipboard.setText(AnnotatedString(it))
        copied = true
    }
    val linkStyle =
        TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val text =
        buildAnnotatedString {
            var cursor = 0
            links.forEach { link ->
                append(body.substring(cursor, link.start))
                withLink(
                    LinkAnnotation.Url(link.url, linkStyle) {
                        failed = !openNoticeLink(link.url, openLink ?: uriHandler::openUri)
                    },
                ) { append(body.substring(link.start, link.end)) }
                cursor = link.end
            }
            append(body.substring(cursor))
        }
    Column {
        SelectionContainer { Text(text, Modifier.testTag("account-notice-body")) }
        FlowRow {
            TextButton({ copy(body) }, Modifier.testTag("notice-copy-body")) {
                Text(stringResource(R.string.account_notice_copy_body))
            }
            if (links.isNotEmpty()) {
                Box {
                    TextButton({ expanded = true }, Modifier.testTag("notice-copy-link-menu")) {
                        Text(stringResource(R.string.account_notice_copy_link))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        links.map { it.url }.distinct().forEachIndexed { index, url ->
                            DropdownMenuItem(
                                text = { Text(url) },
                                onClick = {
                                    copy(url)
                                    expanded = false
                                },
                                modifier = Modifier.testTag("notice-copy-link-$index"),
                            )
                        }
                    }
                }
            }
        }
        if (copied) Text(stringResource(R.string.account_notice_copied))
        if (failed) Text(stringResource(R.string.account_notice_open_failed), color = MaterialTheme.colorScheme.error)
    }
}

private fun openNoticeLink(
    url: String,
    open: (String) -> Unit,
): Boolean =
    try {
        open(url)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
