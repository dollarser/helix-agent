package com.helix.app

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Extracts a local share DRAFT (HXA-056, `ACTION_SEND` / `ACTION_SEND_MULTIPLE`) from a
 * launch intent. The result is DRAFT-ONLY input for the chat composer: the text pre-fills
 * the input box and the image URIs are imported through the existing attachment pipeline
 * (import → classify → normalize → stage). Nothing is ever sent automatically (ADR-0014
 * §5, HXA-049/056: share inputs land locally first, preview, then an explicit user send).
 *
 * The URIs are `content://` references owned by the SHARING app (untrusted input): they
 * are never logged, never persisted, and never resolved outside the import pipeline. The
 * shared text is composer content (user-visible and user-editable before any send), not
 * model content.
 */
object ShareIntentDraft {
    /** One share payload: draft text plus the image references, in share order. */
    data class Draft(
        val text: String?,
        val imageUris: List<String>,
    ) {
        val isEmpty: Boolean
            get() = text.isNullOrEmpty() && imageUris.isEmpty()
    }

    /**
     * Parses [intent] into a [Draft]; non-share intents (or share intents of a type the
     * app does not draft — anything but the `text/plain` and image MIME families) yield an
     * empty draft. Multi-image shares arrive as a `Uri` array under the same
     * [Intent.EXTRA_STREAM] key. (The image family literal lives in the code, never in a
     * KDoc: its two-character wildcard sequence opens a nested block comment inside KDoc
     * and corrupts the parse — a Kotlin lexer quirk fixed by phrasing here.)
     */
    fun draftFrom(intent: Intent): Draft {
        val text: String? =
            if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        val shareType: String? = intent.type
        val images: List<String> =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    if (shareType.orEmpty().startsWith("image/")) {
                        streamUri(intent)?.let { listOf(it) }.orEmpty()
                    } else {
                        emptyList()
                    }
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    if (shareType != null && shareType.startsWith("image/")) {
                        streamUris(intent)
                    } else {
                        emptyList()
                    }
                }

                else -> {
                    emptyList()
                }
            }
        return Draft(text, images)
    }

    private fun streamUri(intent: Intent): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.toString()
        } else {
            legacyStreamUri(intent)
        }

    @Suppress("DEPRECATION") // API 29/32: the single-argument overload is the only one
    private fun legacyStreamUri(intent: Intent): String? =
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()

    @Suppress("DEPRECATION") // EXTRA_STREAM arrives as a Uri[] on every API level (docs-fixed shape)
    private fun streamUris(intent: Intent): List<String> =
        intent.getParcelableArrayExtra(Intent.EXTRA_STREAM)?.mapNotNull { (it as? Uri)?.toString() }.orEmpty()
}
