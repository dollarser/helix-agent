package com.helix.app

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * The system share sheet → Helix adapter (P0-B PX-06 / HXA-056): turns a share INTENT into a
 * local DRAFT — the shared text becomes the one-shot composer pre-fill and the shared file
 * references (images AND office/research documents) become staged ATTACHMENTS through the
 * existing import pipeline. The draft is NEVER sent: ADR-0014 §5 — the user reviews and hits
 * send explicitly.
 *
 * Supported inbound types:
 *  - `text/plain` (and `text/html` carrying no file) → [Draft.text] composer pre-fill.
 *  - images (any image MIME type)                    → [Draft.imageUris] staged as images.
 *  - the closed document set [DOCUMENT_MIME_TYPES]   → [Draft.fileUris] staged as attachments
 *    (the import pipeline classifies + extracts PDF / DOCX / HTML to model-visible text).
 * Anything else is not advertised in the manifest, so it never reaches here.
 *
 * Every inbound value (type, EXTRA_TEXT, EXTRA_STREAM) is UNTRUSTED — this only routes
 * references; the import pipeline re-verifies each one before it is staged.
 *
 * The [buildDraft] core is pure (plain strings + a URI list) so it is unit-testable on the JVM;
 * [draftFrom] is the thin Android adapter that reads the [Intent] and delegates.
 */
object ShareIntentDraft {
    data class Draft(
        val text: String?,
        val imageUris: List<String>,
        val fileUris: List<String>,
    ) {
        val isEmpty: Boolean
            get() = (text?.isEmpty() ?: true) && imageUris.isEmpty() && fileUris.isEmpty()
    }

    /** The closed set of shareable office/research document types (mirrors the on-device
     *  `DocumentTextExtractor` kinds): PDF, DOCX and HTML. */
    private val DOCUMENT_MIME_TYPES =
        setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/html",
        )

    /** The pure share-draft core: routes one share (action, MIME type, shared text, file refs)
     *  into [Draft]. Unit-testable without an [Intent] — plain strings plus a URI list. */
    fun buildDraft(
        action: String?,
        type: String?,
        extraText: String?,
        streamUris: List<String>,
    ): Draft {
        val imageFamily = type.orEmpty().startsWith("image/")
        val imageUris = if (imageFamily) streamUris else emptyList()
        val fileUris = if (!imageFamily && type in DOCUMENT_MIME_TYPES) streamUris else emptyList()
        val hasFile = streamUris.isNotEmpty()
        val rawText =
            when {
                type == "text/plain" -> extraText
                type == "text/html" && !hasFile -> extraText
                else -> null
            }
        val text = if (action == Intent.ACTION_SEND) rawText?.trim()?.takeIf { it.isNotEmpty() } else null
        return Draft(text, imageUris, fileUris)
    }

    /** Reads a share [Intent] (launched with `singleTop`) into a [Draft]. */
    fun draftFrom(intent: Intent): Draft =
        buildDraft(
            action = intent.action,
            type = intent.type,
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
            streamUris =
                when (intent.action) {
                    Intent.ACTION_SEND_MULTIPLE -> streamUris(intent)
                    else -> streamUri(intent)?.let { listOf(it) }.orEmpty()
                },
        )

    /** A single ACTION_SEND reference (EXTRA_STREAM as a Uri, or a one-element array) or null. */
    private fun streamUri(intent: Intent): String? {
        val data =
            intent.getStringExtra(Intent.EXTRA_STREAM) ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return data?.toString() ?: legacyStreamUri(intent)
    }

    /**
     * API < 29: some senders ship EXTRA_STREAM as a raw Parcelable, which
     * `getParcelableExtra` can't cast. Reads it reflectively (no class literal → no crash) and
     * yields the FIRST reference only; a malformed shape yields null (a broken share, never an
     * exception).
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth")
    private fun legacyStreamUri(intent: Intent): String? =
        if (Build.VERSION.SDK_INT < 29) {
            try {
                val raw = intent.extras?.get(Intent.EXTRA_STREAM) ?: return null
                when (raw) {
                    is Uri -> raw.toString()
                    is Array<*> -> raw.firstOrNull { it is Uri }?.let { (it as Uri).toString() }
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    /** The ACTION_SEND_MULTIPLE reference array (EXTRA_STREAM as a Uri[]), or empty. */
    private fun streamUris(intent: Intent): List<String> =
        if (intent.hasExtra(Intent.EXTRA_STREAM)) {
            intent
                .getParcelableArrayExtra(Intent.EXTRA_STREAM)
                ?.filterIsInstance<Uri>()
                ?.map { it.toString() }
                .orEmpty()
        } else {
            emptyList()
        }
}
