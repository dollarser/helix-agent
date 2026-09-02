package com.helix.feature.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Production [SafSourceMetadata] reader: one bounded `OpenableColumns` query (size / display
 * name) plus `getType` for the MIME (MIME has no cursor column). A provider that throws, or
 * reports nothing, yields all-unknown metadata (fail closed: the import pipeline then enforces
 * the hard byte cap on the stream itself and re-verifies the size at EOF).
 *
 * The reported size / MIME / display name remain UNTRUSTED — this class only transcribes what
 * the provider says; the pipeline never acts on them without re-verification (doc 07: SAF
 * provider 谎报 size/MIME/display name).
 */
class ContentResolverSafMetadataReader(
    private val resolver: ContentResolver,
) {
    // a hostile provider may throw anything; unknown metadata degrades to the hard-cap path
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun metadata(uri: String): SafSourceMetadata =
        try {
            val parsed = Uri.parse(uri)
            val mimeType = safeType(parsed)
            val (size, name) =
                resolver
                    .query(
                        parsed,
                        arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use -1L to null
                        longOrNull(cursor, OpenableColumns.SIZE) to stringOrNull(cursor, OpenableColumns.DISPLAY_NAME)
                    }
                    ?: -1L to null
            SafSourceMetadata(size, mimeType, name)
        } catch (e: Exception) {
            // A hostile or broken provider must not break the admission path — unknown
            // metadata degrades the import to the hard-cap path, it does not abort it.
            SafSourceMetadata(-1L, null, null)
        }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // hostile provider: no type is safe
    private fun safeType(uri: Uri): String? =
        try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }

    private fun longOrNull(
        cursor: Cursor,
        column: String,
    ): Long {
        val index = cursor.getColumnIndex(column)
        return if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
    }

    private fun stringOrNull(
        cursor: Cursor,
        column: String,
    ): String? {
        val index = cursor.getColumnIndex(column)
        return if (index < 0) null else cursor.getString(index)
    }
}

/** Production source opener: `ContentResolver.openInputStream`. */
class ContentResolverSafSourceOpener(
    private val resolver: ContentResolver,
) : SafSourceOpener {
    override fun openStream(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("source stream is null")
}

/** Production destination opener: `ContentResolver.openOutputStream` in truncate mode. */
class ContentResolverSafDestinationOpener(
    private val resolver: ContentResolver,
) : SafDestinationOpener {
    override fun openStream(uri: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), "w") ?: throw IOException("destination stream is null")
}

/**
 * Production post-write size verification. Returns -1 (unknown) on any provider failure — a
 * destination that cannot report its size after an export is verified=false, not an error.
 */
class ContentResolverSafDestinationVerifier(
    private val resolver: ContentResolver,
) : SafDestinationVerifier {
    @Suppress("SwallowedException", "TooGenericExceptionCaught") // unreadable size reads as -1 (unverified)
    override fun reportedSize(uri: String): Long =
        try {
            resolver
                .query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use -1L
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
                }
                ?: -1L
        } catch (e: Exception) {
            -1L
        }
}

/**
 * Production 撤销检测 probe for a persisted tree grant: a bounded query on the tree root.
 * The query succeeds iff the grant is still usable — permission revocation, provider uninstall
 * or the user revoking the grant all surface as query failures, which read as "no longer
 * granted" (fail closed).
 */
class ContentResolverSafGrantProbe(
    private val resolver: ContentResolver,
) : SafGrantProbe {
    @Suppress("SwallowedException", "TooGenericExceptionCaught") // any query failure reads as revoked (fail closed)
    override fun isStillGranted(treeUri: String): Boolean =
        try {
            // A live grant returns a cursor over the tree — even an EMPTY one. Revocation,
            // provider uninstall or a revoked grant surface as a query FAILURE (null cursor or
            // exception), never as a successful-but-empty result. The liveness signal is the
            // non-null cursor, NOT the row count: an empty-but-granted folder must not be
            // swept away as revoked.
            resolver.query(Uri.parse(treeUri), null, null, null, null)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
}
