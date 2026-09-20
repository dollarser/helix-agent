package com.helix.core.storage.export

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Resolves one fixed reference after the relation transaction; never scans the content directory. */
internal class SessionExportContent(
    private val store: ContentStore,
    private val sanitize: (String) -> String,
    private val checkCancelled: () -> Unit,
) {
    fun describe(ref: ContentRef): JsonObject {
        checkCancelled()
        return when {
            !store.exists(ref) -> descriptor(ref, "missing", "not_read")
            ref.size > SessionExportFormat.INLINE_BYTES -> descriptor(ref, "reference_only", "not_read")
            else -> describeSmall(ref)
        }
    }

    private fun describeSmall(ref: ContentRef): JsonObject {
        val original =
            try {
                store.readBounded(ref, SessionExportFormat.INLINE_BYTES)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        checkCancelled()
        return if (original == null) unavailable(ref) else verifiedText(ref, original)
    }

    private fun verifiedText(
        ref: ContentRef,
        original: String,
    ): JsonObject {
        // Also reject lossy UTF-8 decoding: the exported text must represent the verified bytes.
        val bytes = original.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() != ref.size || FileContentStore.sha256Hex(bytes) != ref.sha256) {
            return descriptor(ref, "changed", "failed")
        }
        return sanitizedText(ref, original)
    }

    private fun sanitizedText(
        ref: ContentRef,
        original: String,
    ): JsonObject {
        val text = sanitize(original)
        checkCancelled()
        val exported = text.toByteArray(Charsets.UTF_8)
        val redacted = text != original
        if (exported.size > SessionExportFormat.INLINE_BYTES) {
            return descriptor(ref, "omitted_limit", "verified", redacted = redacted)
        }
        return descriptor(ref, if (redacted) "redacted" else "inline", "verified", text, redacted)
    }

    private fun unavailable(ref: ContentRef): JsonObject {
        checkCancelled()
        return descriptor(ref, if (store.exists(ref)) "changed" else "missing", "failed")
    }

    private fun descriptor(
        ref: ContentRef,
        availability: String,
        verification: String,
        text: String? = null,
        redacted: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("contentId", "content:${ref.sha256}")
            put("source", "content_store")
            put("sourceBytes", ref.size)
            put("sourceSha256", ref.sha256)
            put("hashAlgorithm", "SHA-256")
            put("availability", availability)
            put("verification", verification)
            put("redacted", redacted)
            if (text != null) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                put("encoding", "UTF-8")
                put("text", text)
                put("exportedBytes", bytes.size)
                put("exportedSha256", FileContentStore.sha256Hex(bytes))
            }
        }
}
