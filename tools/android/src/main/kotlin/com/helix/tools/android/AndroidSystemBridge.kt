package com.helix.tools.android

/**
 * The synchronous port the `android.*` / `clipboard.*` system tools (HXA-064) execute against.
 *
 * Production is [AndroidSystemBridgeImpl] (Context-backed, in this module); unit tests inject a
 * fake. The port is PURE JVM (no Android types in the interface or the outcomes) and returns
 * small, bounded, no-null view objects; each tool maps one outcome to a Completed / Failed
 * ToolExecutorResult fail-closed — a policy refusal is a stable status, never a fake success.
 *
 * The security controls live in the impl, not the port (roadmap HXA-064, doc 09): `android.open_uri`
 * only opens (http/https → the OS picks the handler, never an auto-follow into a new app — §5.3);
 * `clipboard.read` / `clipboard.write` are refused unless the app is visible-foreground; and
 * `android.share` only builds + launches the ACTION_SEND chooser — the "preview first" of the share
 * input is the L2 approval card, which renders the full canonical arguments (the share text) before
 * the user approves (doc 02 §5.4). The port never throws for a page or system condition (only for a
 * genuine programming error).
 */
interface AndroidSystemBridge {
    /** Opens [url] in the device's system handler (doc 09: `android.open_uri` 只打开; http/https only). */
    fun openUri(url: String): OpenUriOutcome

    /** Reads the system clipboard (roadmap HXA-064: gated by visible-foreground). */
    fun clipboardRead(): ClipboardReadOutcome

    /** Writes [text] to the system clipboard (roadmap HXA-064: gated by visible-foreground). */
    fun clipboardWrite(text: String): ClipboardWriteOutcome

    /**
     * Shares [text] (optional [subject]) through the system chooser (doc 09: `android.share`). The
     * share input is previewed by the L2 approval card before the user approves.
     */
    fun share(
        text: String,
        subject: String,
    ): ShareOutcome
}

/**
 * The clipboard read bound (chars), shared by the `clipboard.read` output schema (tools) and the
 * impl's `take(...)` so the emitted text is always schema-valid.
 */
internal const val MAX_CLIPBOARD_READ: Int = 4_000

enum class OpenUriStatus { OPENED, REFUSED, NO_HANDLER, ERROR }

/**
 * Outcome of [AndroidSystemBridge.openUri]. [url] is the requested URL (echoed back; a blank request
 * still echoes the blank). [reason] carries the refusal category (`scheme` for a non-http/https URL)
 * or a stable note and is "" for a successful open.
 */
data class OpenUriOutcome(
    val status: OpenUriStatus,
    val url: String,
    val reason: String,
)

enum class ClipboardReadStatus { READ, REFUSED, ERROR }

/**
 * Outcome of [AndroidSystemBridge.clipboardRead]. [text] is the bounded clipboard content — "" for an
 * empty clipboard (a successful read of nothing) or for a refusal/error. [length] is the ORIGINAL
 * char count (before bounding); [truncated] is true when [text] was cut to [MAX_CLIPBOARD_READ].
 * [reason] is `not-foreground` for a foreground-gated refusal or an error note; "" for a read.
 */
data class ClipboardReadOutcome(
    val status: ClipboardReadStatus,
    val text: String,
    val length: Int,
    val truncated: Boolean,
    val reason: String,
)

enum class ClipboardWriteStatus { WRITTEN, REFUSED, ERROR }

/**
 * Outcome of [AndroidSystemBridge.clipboardWrite]. [length] is the char count written; [reason] is
 * `not-foreground` for a foreground-gated refusal or an error note; "" for a written clip.
 */
data class ClipboardWriteOutcome(
    val status: ClipboardWriteStatus,
    val length: Int,
    val reason: String,
)

enum class ShareStatus { SHARED, NO_HANDLER, ERROR }

/**
 * Outcome of [AndroidSystemBridge.share]. [reason] is a stable note (`no app to share to`) or an
 * error note; "" for a launched share.
 */
data class ShareOutcome(
    val status: ShareStatus,
    val reason: String,
)
