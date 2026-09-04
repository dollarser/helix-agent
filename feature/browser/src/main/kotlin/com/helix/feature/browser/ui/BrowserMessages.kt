package com.helix.feature.browser.ui

import android.content.Context
import com.helix.feature.browser.BrowserError
import com.helix.feature.browser.BrowserErrorKind
import com.helix.feature.browser.DenialReason
import com.helix.feature.browser.DownloadDenial
import com.helix.feature.browser.DownloadItem
import com.helix.feature.browser.DownloadStatus
import com.helix.feature.browser.LoadError
import com.helix.feature.browser.PolicyBlockedError
import com.helix.feature.browser.R
import com.helix.feature.browser.urlDisplayHost

// The browser UI's locale-boundary text resolvers (HXA-069). This is the ONE place that turns a
// stable identity (BrowserErrorKind, DenialReason, DownloadStatus, DownloadDenial) into a
// user-visible string: every value here is a `stringResource`/`context.getString` reference, so
// each user-visible phrase is a resource key with base + `values-en` + `values-zh-rCN` parity, and
// no translatable wording is assembled by concatenation in a Composable.
//
// The Context is the app's locale-wrapped context (see `AppLanguageStore.wrapForLocale`), so
// `getString`/`stringResource` resolve in the active app language. The stable enums themselves
// never carry CJK: the user wording for DenialReason lives HERE, and the tool-outbound reason is
// `DenialReason.code` (locale-independent).

/** The one-line message for the browser error view and its a11y label. */
fun BrowserError.userMessage(context: Context): String =
    when (this) {
        is LoadError -> {
            loadErrorMessage(context, kind, failingUrl)
        }

        is PolicyBlockedError -> {
            context.getString(R.string.browser_error_policy_blocked, reason.userLabel(context))
        }
    }

/** The user-facing wording for a [DenialReason] (distinct from its stable [DenialReason.code]). */
fun DenialReason.userLabel(context: Context): String =
    context.getString(
        when (this) {
            DenialReason.EMPTY -> R.string.browser_denial_reason_empty
            DenialReason.INVALID -> R.string.browser_denial_reason_invalid
            DenialReason.UNSUPPORTED_SCHEME -> R.string.browser_denial_reason_scheme
            DenialReason.MISSING_HOST -> R.string.browser_denial_reason_host
        },
    )

/** The one-line status text for a download row. */
fun downloadStatusText(
    context: Context,
    item: DownloadItem,
): String =
    when (item.status) {
        DownloadStatus.PENDING_CHOICE -> {
            context.getString(R.string.browser_download_pending)
        }

        DownloadStatus.SAVING -> {
            context.getString(R.string.browser_download_saving)
        }

        DownloadStatus.SAVED -> {
            context.getString(R.string.browser_download_saved)
        }

        DownloadStatus.FAILED -> {
            context.getString(
                R.string.browser_download_failed,
                item.detail ?: context.getString(R.string.browser_unknown_reason),
            )
        }

        DownloadStatus.DENIED -> {
            context.getString(R.string.browser_download_denied, denialText(context, item.denial))
        }
    }

/** The user-facing wording for a download denial. */
fun denialText(
    context: Context,
    denial: DownloadDenial?,
): String =
    when (denial) {
        DownloadDenial.URL -> context.getString(R.string.browser_denial_url)
        DownloadDenial.UNSAFE_TYPE -> context.getString(R.string.browser_denial_type)
        DownloadDenial.SIZE -> context.getString(R.string.browser_denial_size)
        DownloadDenial.NAME -> context.getString(R.string.browser_denial_name)
        null -> context.getString(R.string.browser_unknown_reason)
    }

/**
 * The [LoadError] message for a [BrowserErrorKind]. At most a SANITIZED host reaches the text —
 * the failing URL is UNTRUSTED web content (doc 09 §3.4), so the raw URL and Chromium/WebView code
 * never do.
 */
private fun loadErrorMessage(
    context: Context,
    kind: BrowserErrorKind,
    url: String?,
): String {
    val target =
        urlDisplayHost(url)
            ?.let { context.getString(R.string.browser_error_host_suffix, it) }
            .orEmpty()
    return when (kind) {
        BrowserErrorKind.HOST_LOOKUP_FAILED -> {
            context.getString(R.string.browser_error_host_lookup) + target
        }

        BrowserErrorKind.CONNECTION_FAILED -> {
            context.getString(R.string.browser_error_connection) + target
        }

        BrowserErrorKind.TIMEOUT -> {
            context.getString(R.string.browser_error_timeout) + target
        }

        BrowserErrorKind.SSL -> {
            context.getString(R.string.browser_error_ssl) + target
        }

        // POLICY_BLOCKED never reaches a LoadError (that is PolicyBlockedError); the unknown-load
        // wording is the safe fallback for both.
        BrowserErrorKind.POLICY_BLOCKED, BrowserErrorKind.UNKNOWN -> {
            context.getString(R.string.browser_error_unknown) + target
        }
    }
}
