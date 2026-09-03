package com.helix.feature.files

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.helix.core.workspace.ScopeNotAvailable
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/**
 * Production [SafTreeDestination] (HXA-058 导出到用户已授权 tree). Maps the user-chosen
 * (parent path, file name) onto the tree's opaque document ids through the real
 * `ContentResolver` / `DocumentsContract`:
 *
 * - the grant's tree URI is resolved from [SafTreeGrantRegistry] (the caller re-verified the
 *   grant in WRITE mode through [SafTreeScopeService] before calling this seam — this adapter
 *   only performs the governed document walk + create);
 * - the parent directory is walked segment by segment with the browse reader's ambiguity rule:
 *   a segment matching no child — or several — fails closed ([FileNotFoundException]);
 * - a same-name FILE already in the parent is a conflict ([FileAlreadyExistsException]) unless
 *   [overwrite] is true, in which case that document's URI is returned (the export's truncate
 *   write is the overwrite — a directory name is never overwritten);
 * - a new name goes through the create-document insert (the operation
 *   `DocumentsContract.createDocument` performs internally; it requires the tree's WRITE
 *   permission — a read-only grant surfaces as a creation failure and fails closed).
 *
 * No exception message ever carries the tree URI, a document id or a `content://` string
 * (doc 10: the raw URI stays in the platform layer).
 */
class ContentResolverSafTreeDestination(
    private val resolver: ContentResolver,
    private val grants: SafTreeGrantRegistry,
) : SafTreeDestination {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // provider failures map to one stable IOException
    override fun destinationUri(
        scopeId: String,
        parentPath: String,
        displayName: String,
        mimeType: String,
        overwrite: Boolean,
    ): String {
        val grant = grants.find(scopeId) ?: throw notAvailable(scopeId)
        val treeUri = grant.treeUri
        val authority = treeUriAuthority(treeUri) ?: throw notAvailable(scopeId)
        val rootDocId = treeUriRootDocumentId(treeUri) ?: throw notAvailable(scopeId)
        val parentDocId = walkToDirectory(authority, rootDocId, parentPath)
        val sameName = children(authority, parentDocId).filter { it.displayName == displayName && !it.isDirectory }
        return when (sameName.size) {
            1 -> {
                val existing = sameName.single()
                if (overwrite) {
                    DocumentsContract.buildDocumentUri(authority, existing.documentId).toString()
                } else {
                    throw FileAlreadyExistsException(
                        displayName,
                        null,
                        "a document with that name exists in the destination",
                    )
                }
            }

            0 -> {
                try {
                    // The exact operation DocumentsContract.createDocument performs internally:
                    // an insert on buildDocumentUriUsingTree(treeUri, parentDocId) —
                    // `content://<auth>/tree/<rootDocId>/document/<parentDocId>` (the tree-scoped
                    // document URI; logcat-verified shape) — with MIME + display name. (The 5-arg
                    // createDocument overload is not present in this compile SDK's stubs, so the
                    // equivalent insert is issued directly.)
                    val values =
                        ContentValues().apply {
                            put(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
                            put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
                        }
                    val created =
                        resolver
                            .insert(
                                DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), parentDocId),
                                values,
                            )
                            ?: throw IOException("the destination provider created no document")
                    created.toString()
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    // A SecurityException (read-only grant) or a broken provider: one stable
                    // failure, never the raw exception text (it may name the URI).
                    throw IOException("the destination document could not be created")
                }
            }

            else -> {
                throw FileNotFoundException("the destination directory has an ambiguous name")
            }
        }
    }

    /** Walks to the DIRECTORY at [relativePath] ("" = tree root); ambiguous/missing fails closed. */
    private fun walkToDirectory(
        authority: String,
        rootDocId: String,
        relativePath: String,
    ): String {
        var docId = rootDocId
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            val matches = children(authority, docId).filter { it.displayName == segment && it.isDirectory }
            if (matches.size != 1) throw FileNotFoundException("the destination directory path was not found")
            docId = matches.single().documentId
        }
        return docId
    }

    /**
     * The immediate children of [parentDocId]. A failing query — or a **null** cursor
     * (`ContentResolver.query` returns null, it does NOT throw, when no provider answers the
     * authority) — means the destination cannot be enumerated, so the conflict decision cannot
     * be made: fail closed instead of assuming "no same-name document".
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failing query = missing/ambiguous parent
    private fun children(
        authority: String,
        parentDocId: String,
    ): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, parentDocId)
        val cursor: Cursor? =
            try {
                resolver.query(uri, DOC_COLUMNS, null, null, null)
            } catch (e: Exception) {
                throw FileNotFoundException("the destination directory path was not found")
            }
        if (cursor == null) throw FileNotFoundException("the destination directory path was not found")
        val out = mutableListOf<Child>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                if (id != null && name != null) {
                    out.add(Child(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
        return out
    }

    private class Child(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
    )

    private fun notAvailable(scopeId: String): ScopeNotAvailable =
        ScopeNotAvailable("SAF tree scope not available: $scopeId")

    private companion object {
        val DOC_COLUMNS: Array<String> =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
    }
}
