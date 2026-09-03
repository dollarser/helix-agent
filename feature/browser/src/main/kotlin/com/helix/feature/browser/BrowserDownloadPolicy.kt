package com.helix.feature.browser

import java.net.URI
import java.net.URISyntaxException

/**
 * The browser download admission policy (HXA-060; doc 09 §3.4: downloads must limit
 * protocol / size / type and must never auto-execute or install APK/DEX/JAR/SO).
 *
 * Only absolute `http(s)` URLs with a host are downloadable: the page itself may not
 * load non-http schemes, and a download target that points back at `file:` or `data:`
 * is not a network resource Helix will stream. A declared content-length above
 * [MAX_DOWNLOAD_BYTES] is denied up front; an unknown length is allowed and the
 * streaming writer enforces the same cap byte-for-byte.
 */
data class DownloadRequest(
    val url: String,
    val suggestedName: String,
    val mimeType: String?,
    /** -1 when the server declared no content-length. */
    val contentLength: Long,
)

sealed interface DownloadDecision {
    /** May proceed; [targetName] is the sanitized name the UI pre-fills. */
    data class Save(
        val targetName: String,
        val declaredBytes: Long,
    ) : DownloadDecision

    data class Denied(
        val reason: DownloadDenial,
    ) : DownloadDecision
}

enum class DownloadDenial {
    /** The download URL is not an absolute http(s) URL with a host. */
    URL,

    /** Executable / installable file suffix (apk/apks/dex/jar/so/jni) or Android payload MIME. */
    UNSAFE_TYPE,

    /** Declared content-length exceeds [BrowserDownloadPolicy.MAX_DOWNLOAD_BYTES]. */
    SIZE,

    /** The suggested name is empty once directory parts, control chars and leading dots are removed. */
    NAME,
}

object BrowserDownloadPolicy {
    /** 100 MiB. The streaming writer enforces the same cap when the length is unknown. */
    const val MAX_DOWNLOAD_BYTES: Long = 100L * 1024L * 1024L

    const val MAX_NAME_LENGTH = 128

    val BLOCKED_SUFFIXES = setOf("apk", "apks", "dex", "jar", "so", "jni")

    private val BLOCKED_MIME_PREFIXES = listOf("application/vnd.android", "application/x-dex")

    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    /** One fail-closed denial return per gate condition (URL / name / type / MIME / size). */
    @Suppress("ReturnCount")
    fun evaluate(request: DownloadRequest): DownloadDecision {
        if (!isHttpUrl(request.url)) return DownloadDecision.Denied(DownloadDenial.URL)
        val name = sanitizeName(request.suggestedName)
        if (name.isEmpty()) return DownloadDecision.Denied(DownloadDenial.NAME)
        if (suffixOf(name) in BLOCKED_SUFFIXES) return DownloadDecision.Denied(DownloadDenial.UNSAFE_TYPE)
        request.mimeType?.lowercase()?.let { mime ->
            if (BLOCKED_MIME_PREFIXES.any { mime.startsWith(it) }) {
                return DownloadDecision.Denied(DownloadDenial.UNSAFE_TYPE)
            }
        }
        if (request.contentLength > MAX_DOWNLOAD_BYTES) return DownloadDecision.Denied(DownloadDenial.SIZE)
        val declared = if (request.contentLength > 0) request.contentLength else -1L
        return DownloadDecision.Save(name, declared)
    }

    /**
     * The gate the controller re-applies at download execution time (doc 09 §3.4).
     *
     * Unparseable input IS the denial: the swallowed exception and the `false` result are
     * the same fail-closed decision.
     */
    @Suppress("SwallowedException")
    internal fun isHttpUrl(raw: String): Boolean =
        try {
            val uri = URI(raw.trim())
            uri.isAbsolute &&
                uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank()
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: URISyntaxException) {
            false
        }

    /**
     * Strips directory parts (forward AND back slashes — the page suggests either),
     * control characters, surrounding whitespace and leading dots; caps at
     * [MAX_NAME_LENGTH]. Pure: the same name in, the same name out.
     */
    internal fun sanitizeName(suggested: String): String =
        suggested
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(CONTROL_CHARS, "")
            .trim()
            .trimStart('.')
            .take(MAX_NAME_LENGTH)

    internal fun suffixOf(name: String): String = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
