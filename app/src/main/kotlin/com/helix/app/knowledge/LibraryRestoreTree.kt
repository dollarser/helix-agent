package com.helix.app.knowledge

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.helix.core.storage.content.DocumentReadAccess
import com.helix.core.storage.content.DocumentStorage

/** One explicitly selected tree, read-only and transient. Never persists/mints an OS grant. */
internal class LibraryRestoreTree(
    private val resolver: ContentResolver,
    private val tree: Uri,
    flags: Int,
) : DocumentReadAccess {
    private data class Node(
        val reference: String,
        val name: String,
        val directory: Boolean,
    )

    private val observed = linkedMapOf<String, Node>()

    init {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    override fun list(directory: String): List<DocumentStorage.File> {
        require(directory in setOf("notes", "sources") || directory.matches(Regex("originals/[A-Za-z0-9_-]{1,100}")))
        var parent = DocumentsContract.getTreeDocumentId(tree)
        for (segment in directory.split('/')) {
            val matching = children(parent).filter { it.name == segment }
            require(matching.size <= 1)
            val node = matching.singleOrNull() ?: return emptyList()
            require(node.directory)
            parent = node.reference
        }
        val entries = children(parent)
        require(entries.none { it.directory })
        var total = 0L
        return entries.map {
            observed[it.reference] = it
            read(it.reference, 16 * 1024 * 1024).also { file ->
                total += file.bytes.size
                require(total <= 64 * 1024 * 1024)
            }
        }
    }

    override fun read(
        reference: String,
        maxBytes: Int,
    ): DocumentStorage.File {
        require(maxBytes in 1..16 * 1024 * 1024)
        val node = requireNotNull(observed[reference])
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, reference)
        val output = java.io.ByteArrayOutputStream()
        requireNotNull(resolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes)
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        return DocumentStorage.File(reference, node.name, bytes, DocumentStorage.hash(bytes))
    }

    private fun children(parent: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return requireNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 1024)
                    val name = cursor.getString(1)
                    require(
                        name.isNotBlank() && name !in setOf(".", "..") &&
                            name.none { it == '/' || it == '\\' || it.isISOControl() },
                    )
                    add(Node(cursor.getString(0), name, cursor.getString(2) == Document.MIME_TYPE_DIR))
                }
                require(distinctBy { it.name }.size == size)
                require(distinctBy { it.reference }.size == size)
            }
        }
    }
}
