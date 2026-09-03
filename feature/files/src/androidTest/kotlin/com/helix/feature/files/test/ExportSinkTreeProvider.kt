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
 * HXA-058 (device gate): a MUTABLE SAF document tree — the destination side. It answers the same
 * `DocumentsContract` query URIs as [TreeDocumentsProvider] PLUS the create-document insert
 * (`content://<auth>/tree/<rootDocId>/document/<parentDocId>`, the exact URI shape
 * `DocumentsContract.buildDocumentUriUsingTree` builds — the operation
 * `DocumentsContract.createDocument` performs internally) and a READ/WRITE [openFile], so the
 * REAL [com.helix.feature.files.ContentResolverSafTreeDestination] and the real export
 * re-read/size re-check can be driven through the REAL `ContentResolver`.
 *
 * The tree (fresh per install; created documents persist in-process and in files):
 * ```
 * root/               (directory)
 * ├── note.txt        "v1"   (a stable same-name conflict target)
 * └── dup/            (directory)
 *     ├── same.txt    "1"
 *     └── same.txt    "2"    (ambiguous child names)
 * ```
 * Created documents are backed by files in `filesDir/exportsink/`, so a write made through
 * `openFile("w")` is visible to the subsequent size query and re-read `openFile("r")` — exactly
 * the post-export verification path.
 */
class ExportSinkTreeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // A fresh install keeps app data (`pm install -r`): clear any previous run's backing
        // files so the in-memory seed and the on-disk bytes can never disagree.
        sinkDir.mkdirs()
        sinkDir.listFiles()?.forEach { it.delete() }
        return true
    }

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
            // `content://<auth>/document/<parentId>/children` — the child listing.
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

    /**
     * The create-document insert: `content://<auth>/tree/<rootDocId>/document/<parentDocId>` —
     * the EXACT shape `DocumentsContract.buildDocumentUriUsingTree` builds (`treeUri
     * .buildUpon().appendPath("document").appendPath(documentId)`, logcat-verified on API 29/36)
     * — the operation `DocumentsContract.createDocument` performs internally. The framework's
     * `DocumentsProvider` UriMatcher matches this as `tree/#/document/#`
     * (CREATE_DOCUMENT_IN_TREE); a plain fixture provider must match the same 4-segment shape.
     */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? {
        val segments = uri.pathSegments
        if (segments.size != 4 || segments[0] != "tree" || segments[2] != "document") return null
        val parent = document(segments[3]) ?: throw FileNotFoundException("no such parent: ${segments[3]}")
        if (!parent.isDir) throw FileNotFoundException("parent is not a directory: ${segments[3]}")
        val id = "d" + nextId++
        val name = values?.getAsString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: "new-document"
        val mime =
            values?.getAsString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                ?: "application/octet-stream"
        docs[id] = Doc(id, name, isDir = false, mime = mime, file = backingFile(id))
        backingFile(id).let { if (!it.exists()) it.createNewFile() }
        children.getOrPut(segments[3]) { mutableListOf() } += id
        return Uri.parse("content://$AUTHORITY/document/$id")
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val segments = uri.pathSegments
        require(segments.size == 2 && segments[0] == "document") { "not a document URI" }
        val file = document(segments[1])?.file ?: throw FileNotFoundException("no such file document: ${segments[1]}")
        return if (mode == "r") {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }
    }

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

    // ── The mutable tree ──────────────────────────────────────────────────────────────────

    private data class Doc(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val mime: String = "text/plain",
        val file: File? = null,
    )

    private val docs = linkedMapOf<String, Doc>()
    private val children = linkedMapOf<String, MutableList<String>>()
    private var nextId = 0

    private val sinkDir: File
        get() = File(context!!.filesDir, "exportsink")

    private fun seed() {
        if (docs.isNotEmpty()) return
        docs["root"] = Doc("root", "root", isDir = true, mime = DocumentsContract.Document.MIME_TYPE_DIR)
        docs["note"] = Doc("note", "note.txt", isDir = false, file = backingFile("note"))
        backingFile("note").writeBytes("v1".toByteArray())
        docs["dup"] = Doc("dup", "dup", isDir = true, mime = DocumentsContract.Document.MIME_TYPE_DIR)
        docs["s1"] = Doc("s1", "same.txt", isDir = false, file = backingFile("s1"))
        backingFile("s1").writeBytes("1".toByteArray())
        docs["s2"] = Doc("s2", "same.txt", isDir = false, file = backingFile("s2"))
        backingFile("s2").writeBytes("2".toByteArray())
        children["root"] = mutableListOf("note", "dup")
        children["dup"] = mutableListOf("s1", "s2")
    }

    private fun backingFile(id: String) = File(sinkDir, "$id.bin")

    private fun document(id: String): Doc? {
        seed()
        return docs[id]
    }

    private fun childrenOf(parentId: String): List<Doc> {
        seed()
        return children[parentId].orEmpty().mapNotNull { docs[it] }
    }

    /** The row in the EXACT column order [COLUMNS] (id, name, mime, size). */
    private fun rowFor(doc: Doc): Array<Any?> =
        arrayOf(
            doc.id,
            doc.name,
            doc.mime,
            if (doc.isDir) -1L else (doc.file?.takeIf { it.exists() }?.length() ?: -1L),
        )

    companion object {
        // Same order the governed queries project (id, display name, MIME, size).
        val COLUMNS: Array<String> =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )

        const val AUTHORITY = "com.helix.feature.files.exportsink"

        val TREE_URI: String = "content://$AUTHORITY/tree/root"
    }
}
