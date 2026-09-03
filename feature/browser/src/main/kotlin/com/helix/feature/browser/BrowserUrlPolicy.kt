package com.helix.feature.browser

import java.net.URI
import java.net.URISyntaxException

/**
 * The browser feature's URL admission policy (HXA-060; doc 09 §3.4 WebView 安全约束).
 *
 * Only a closed set of targets may ever be loaded in a Helix tab (or fetched for a
 * download): `http` / `https` with a non-empty host, the empty-document `about:blank`,
 * and inline `data:text/html` documents. Everything else — `file`, `content`,
 * `intent`, `javascript`, `view-source`, other `about:` pages, unknown schemes — is
 * denied. The policy FAILS CLOSED: a URL that cannot be parsed at all is denied
 * (INVALID) rather than passed to the platform parser, and a `data:` document whose
 * media type is not exactly `text/html` is denied.
 *
 * The policy is pure and JVM-tested; the WebView layer applies it in exactly ONE
 * place (the navigation choke point in [BrowserTabController.navigate] plus the
 * download path), so no WebView call site can bypass it.
 */
sealed interface BrowserUrlDecision {
    /** The URL may be loaded in a Helix tab; [url] is the exact string to load. */
    data class Allowed(
        val url: String,
    ) : BrowserUrlDecision

    data class Denied(
        val reason: DenialReason,
    ) : BrowserUrlDecision
}

enum class DenialReason(
    val label: String,
) {
    /** Empty or whitespace-only input. */
    EMPTY("地址为空"),

    /** Not parseable as an absolute URI, or contains control characters. */
    INVALID("地址无法解析"),

    /** Parseable, but the scheme (or `about`/`data` sub-form) is not loadable in a Helix tab. */
    UNSUPPORTED_SCHEME("不支持的地址协议"),

    /** `http(s)` without a non-empty host. */
    MISSING_HOST("地址缺少主机名"),
}

object BrowserUrlPolicy {
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    /**
     * One fail-closed denial return per gate condition (empty / control chars / data /
     * unparseable (both exception spellings) / non-absolute / ...). Unparseable input IS
     * the INVALID denial: the swallowed exception and the Denied result are the same
     * fail-closed decision.
     */
    @Suppress("ReturnCount", "SwallowedException")
    fun evaluate(raw: String): BrowserUrlDecision {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return BrowserUrlDecision.Denied(DenialReason.EMPTY)
        if (CONTROL_CHARS.containsMatchIn(candidate)) {
            return BrowserUrlDecision.Denied(DenialReason.INVALID)
        }
        if (candidate.startsWith(DATA_PREFIX, ignoreCase = true)) {
            return evaluateData(candidate)
        }
        val uri =
            try {
                URI(candidate)
            } catch (e: IllegalArgumentException) {
                return BrowserUrlDecision.Denied(DenialReason.INVALID)
            } catch (e: URISyntaxException) {
                // Android libcore (and the pinned host JDK) surface malformed input as the
                // checked URISyntaxException; both spellings mean "unparseable".
                return BrowserUrlDecision.Denied(DenialReason.INVALID)
            }
        if (!uri.isAbsolute) return BrowserUrlDecision.Denied(DenialReason.INVALID)
        val scheme = uri.scheme?.lowercase() ?: return BrowserUrlDecision.Denied(DenialReason.INVALID)
        return when (scheme) {
            "http", "https" -> {
                val host = uri.host
                if (host.isNullOrBlank()) {
                    BrowserUrlDecision.Denied(DenialReason.MISSING_HOST)
                } else {
                    BrowserUrlDecision.Allowed(uri.toString())
                }
            }

            "about" -> {
                if (uri.toString().equals(ABOUT_BLANK, ignoreCase = true)) {
                    BrowserUrlDecision.Allowed(uri.toString())
                } else {
                    BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME)
                }
            }

            else -> {
                BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME)
            }
        }
    }

    /**
     * `data:[<mediatype>][;base64],<data>`: payloads legally contain raw markup —
     * `data:text/html,<h1>hi</h1>` — that the strict URI parser rejects, so the media
     * type (characters up to the first `;` or `,`) is checked on the raw string and the
     * already-validated document passes through verbatim.
     */
    private fun evaluateData(candidate: String): BrowserUrlDecision {
        val body = candidate.substring(DATA_PREFIX.length)
        val end = body.indexOfFirst { it == ';' || it == ',' }
        val mediaType = body.substring(0, if (end < 0) body.length else end).trim().lowercase()
        if (mediaType != DATA_TEXT_HTML) {
            return BrowserUrlDecision.Denied(DenialReason.UNSUPPORTED_SCHEME)
        }
        return BrowserUrlDecision.Allowed(candidate)
    }

    private const val ABOUT_BLANK = "about:blank"
    private const val DATA_PREFIX = "data:"
    private const val DATA_TEXT_HTML = "text/html"
}
