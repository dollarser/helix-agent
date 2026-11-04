package com.helix.feature.files.test

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * HXA-058 (device gate): a MUTABLE SAF document tree — the destination side. It answers the same
 * `DocumentsContract` query URIs as [TreeDocumentsProvider] and the standard create-document call
 * with tree-scoped document identities and a READ/WRITE [openDocument], so the
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
class ExportSinkTreeProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        // A fresh install keeps app data (`pm install -r`): clear any previous run's backing
        // files so the in-memory seed and the on-disk bytes can never disagree.
        sinkDir.mkdirs()
        sinkDir.listFiles()?.forEach { it.delete() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID)).apply {
            addRow(arrayOf("root", "root"))
        }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor =
        MatrixCursor(COLUMNS).apply {
            document(documentId)?.let { addRow(rowFor(it)) }
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        MatrixCursor(COLUMNS).apply {
            childrenOf(parentDocumentId).forEach { addRow(rowFor(it)) }
        }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean =
        document(parentDocumentId)?.isDir == true && (
            parentDocumentId == "root" ||
                childrenOf(parentDocumentId).any { it.id == documentId }
        )

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = document(parentDocumentId) ?: throw FileNotFoundException("no such parent")
        require(parent.isDir) { "parent is not a directory" }
        val id = "d" + nextId++
        docs[id] = Doc(id, displayName, isDir = false, mime = mimeType, file = backingFile(id))
        check(backingFile(id).createNewFile())
        children.getOrPut(parentDocumentId) { mutableListOf() } += id
        return id
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = document(documentId)?.file ?: throw FileNotFoundException("no such file document")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

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
