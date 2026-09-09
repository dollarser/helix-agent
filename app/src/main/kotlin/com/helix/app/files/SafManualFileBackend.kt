package com.helix.app.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeScopeService
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** Tree URIs stay inside this Android adapter. Provider flags are checked per operation. */
@Suppress("TooManyFunctions") // The backend contract plus tree resolution and metadata helpers.
internal class SafManualFileBackend(
    private val resolver: ContentResolver,
    private val grants: SafGrantStore,
    private val service: SafTreeScopeService,
    private val scopeId: String,
) : ManualFileBackend {
    private data class Node(
        val uri: Uri,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val flags: Long,
    )

    private fun tree(write: Boolean = false): Uri {
        service.resolve(scopeId, if (write) SafAccessMode.WRITE else SafAccessMode.READ)
        return requireNotNull(grants.find(scopeId)).treeUri.toUri()
    }

    @Suppress("ReturnCount") // Stop at a missing path segment.
    private fun lookup(path: String, write: Boolean = false): Node? {
        val relative = FileScopePath(scopeId, path).relativePath
        val tree = tree(write)
        var node =
            metadata(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        if (relative.isEmpty()) return node
        for (part in relative.split('/')) {
            require(node.directory) { "Parent is not a folder" }
            val matches = entries(node.uri).filter { it.name == part }
            require(matches.size <= 1) { "Provider returned duplicate names" }
            node = matches.singleOrNull() ?: return null
        }
        return node
    }

    private fun metadata(uri: Uri): Node =
        requireNotNull(resolver.query(uri, COLUMNS, null, null, null)).use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException("Document unavailable")
            Node(
                uri,
                cursor.getString(1),
                cursor.getString(2) == Document.MIME_TYPE_DIR,
                cursor.getLong(3),
                cursor.getLong(4),
            )
        }

    private fun entries(parent: Uri): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val query = resolver.query(uri, COLUMNS, null, null, null)
        return requireNotNull(query) { "Provider listing failed" }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    require(name.isNotBlank() && name !in listOf(".", "..")) { "Invalid document name" }
                    require(!name.contains('/')) { "Invalid document separator" }
                    require(!name.contains('\\')) { "Invalid document separator" }
                    add(
                        Node(
                            DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)),
                            name,
                            cursor.getString(2) == Document.MIME_TYPE_DIR,
                            cursor.getLong(3),
                            cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    override fun validateMutation(path: String) {
        require(!FileScopePath(scopeId, path).isRoot)
        tree(true)
    }

    override fun stat(path: String): ManualFileInfo? = lookup(path)?.let { ManualFileInfo(it.directory, it.size) }

    override fun children(path: String): List<String> =
        entries(requireNotNull(lookup(path)).uri).map { it.name }.also {
            require(it.distinct().size == it.size) { "Provider returned duplicate names" }
        }

    override fun read(path: String): InputStream =
        resolver.openInputStream(requireNotNull(lookup(path)).uri)
            ?: throw FileNotFoundException("Cannot read document")

    override fun create(
        path: String,
        directory: Boolean,
    ) {
        val ref = FileScopePath(scopeId, path)
        require(!ref.isRoot)
        check(lookup(path, true) == null) { "Destination already exists" }
        val parent = requireNotNull(lookup(ref.parent.relativePath, true))
        require(parent.flags and Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L) {
            "Provider does not support creating files"
        }
        val uri =
            requireNotNull(
                DocumentsContract.createDocument(
                    resolver,
                    parent.uri,
                    if (directory) Document.MIME_TYPE_DIR else "application/octet-stream",
                    ref.name,
                ),
            ) { "Provider did not create document" }
        val created = requireNotNull(lookup(path)) { "Created document is absent from its parent" }
        check(sameDocument(created.uri, uri)) { "Provider returned a different document" }
        check(
            created.name == ref.name && created.directory == directory,
        ) { "Provider changed the requested name or type" }
    }

    override fun write(path: String): OutputStream {
        val node = requireNotNull(lookup(path, true))
        require(node.flags and Document.FLAG_SUPPORTS_WRITE.toLong() != 0L) { "Provider does not support writing" }
        return resolver.openOutputStream(node.uri, "wt") ?: throw FileNotFoundException("Cannot write document")
    }

    override fun rename(
        path: String,
        destination: String,
    ) {
        val source = FileScopePath(scopeId, path)
        val target = FileScopePath(scopeId, destination)
        require(!source.isRoot && !target.isRoot && source.parent == target.parent)
        val node = requireNotNull(lookup(path, true))
        require(lookup(destination, true) == null) { "Destination already exists" }
        require(node.flags and Document.FLAG_SUPPORTS_RENAME.toLong() != 0L) { "Provider does not support renaming" }
        val result =
            requireNotNull(
                DocumentsContract.renameDocument(resolver, node.uri, target.name),
            ) { "Provider rename failed" }
        val renamed = requireNotNull(lookup(destination)) { "Renamed document is absent from its parent" }
        check(sameDocument(renamed.uri, result) && lookup(path) == null) {
            "Provider rename was not confirmed"
        }
    }

    override fun delete(path: String) {
        require(!FileScopePath(scopeId, path).isRoot)
        val node = requireNotNull(lookup(path, true))
        require(node.flags and Document.FLAG_SUPPORTS_DELETE.toLong() != 0L) { "Provider does not support deletion" }
        check(DocumentsContract.deleteDocument(resolver, node.uri) && lookup(path) == null) {
            "Provider deletion was not confirmed"
        }
    }

    private fun sameDocument(
        expected: Uri,
        actual: Uri,
    ): Boolean =
        expected.authority == actual.authority &&
            DocumentsContract.getDocumentId(expected) == DocumentsContract.getDocumentId(actual)

    companion object {
        private val COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_FLAGS,
            )
    }
}
