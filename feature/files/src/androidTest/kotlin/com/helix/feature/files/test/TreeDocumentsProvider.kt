package com.helix.feature.files.test

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException

/**
 * HXA-057 (device gate): a small, FIXED document tree served through the same URI shapes a real
 * SAF provider answers — `content://<auth>/document/<id>/children` (the child listing, the exact
 * shape `DocumentsContract.buildChildDocumentsUri` builds) and
 * `content://<auth>/document/<id>` (a single document / its bytes) — so the REAL
 * [com.helix.feature.files.ContentResolverSafTreeReader] can be driven through the REAL
 * `ContentResolver` (the same code path a host provider takes).
 *
 * The tree:
 * ```
 * root/               (directory)
 * ├── a.txt           "hello"  (5 bytes)
 * ├── sub/            (directory)
 * │   └── b.txt       "x"      (1 byte)
 * └── dup/            (directory)
 *     ├── same.txt    "1"      (1 byte)   ← same display name
 *     └── same.txt    "2"      (1 byte)   ← ambiguous name (fail closed)
 * ```
 *
 * [DocumentsContentProvider] is not part of the compile-stub platform jar, so this is a plain
 * [ContentProvider] that answers the `DocumentsContract` query URIs directly — which is exactly
 * what the reader issues, so the reader is exercised end to end.
 */
class TreeDocumentsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val segments = uri.pathSegments
        val cursor = MatrixCursor(COLUMNS)
        when {
            // `content://<auth>/document/<parentId>/children` — the child listing (the exact
            // segment `DocumentsContract.buildChildDocumentsUri` emits — "children", plural).
            segments.size == 3 && segments[0] == "document" && segments[2] == "children" -> {
                childrenOf(segments[1]).forEach { cursor.addRow(rowFor(it)) }
            }

            // `content://<auth>/document/<docId>` — a single document's metadata.
            segments.size == 2 && segments[0] == "document" -> {
                document(segments[1])?.let { cursor.addRow(rowFor(it)) }
            }

            // Any other shape (e.g. the tree-root liveness query) answers an empty-but-live cursor.
            else -> {
                Unit
            }
        }
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val segments = uri.pathSegments
        require(segments.size == 2 && segments[0] == "document") { "not a document URI" }
        val bytes =
            document(segments[1])?.bytes
                ?: throw FileNotFoundException("no such document: ${segments[1]}")
        val file = File(context!!.cacheDir, "tree-${segments[1]}.bin")
        file.writeBytes(bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    // ── The fixed tree ────────────────────────────────────────────────────────────────────

    private data class Doc(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val mtime: Long,
        val bytes: ByteArray? = null,
    )

    private val docs: Map<String, Doc> =
        mapOf(
            "root" to Doc("root", "root", isDir = true, size = -1L, mtime = 123L),
            "a" to Doc("a", "a.txt", isDir = false, size = 5L, mtime = 123L, "hello".toByteArray()),
            "sub" to Doc("sub", "sub", isDir = true, size = -1L, mtime = 123L),
            "b" to Doc("b", "b.txt", isDir = false, size = 1L, mtime = 123L, "x".toByteArray()),
            "dup" to Doc("dup", "dup", isDir = true, size = -1L, mtime = 123L),
            "s1" to Doc("s1", "same.txt", isDir = false, size = 1L, mtime = 123L, "1".toByteArray()),
            "s2" to Doc("s2", "same.txt", isDir = false, size = 1L, mtime = 123L, "2".toByteArray()),
        )

    private val children: Map<String, List<String>> =
        mapOf(
            "root" to listOf("a", "sub", "dup"),
            "sub" to listOf("b"),
            "dup" to listOf("s1", "s2"),
        )

    private fun document(id: String): Doc? = docs[id]

    private fun childrenOf(parentId: String): List<Doc> = children[parentId].orEmpty().mapNotNull { docs[it] }

    /** The row in the EXACT column order [COLUMNS] (the reader reads these by fixed position). */
    private fun rowFor(doc: Doc): Array<Any?> =
        arrayOf(
            doc.id,
            doc.name,
            if (doc.isDir) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain",
            doc.size,
            doc.mtime,
        )

    companion object {
        // Must stay in the same order the reader requests (it reads positions 0..4).
        val COLUMNS: Array<String> =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        const val AUTHORITY = "com.helix.feature.files.tree"
    }
}
