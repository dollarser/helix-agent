package com.helix.feature.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.helix.core.workspace.ScopeNotAvailable
import java.util.ArrayDeque

/**
 * Production [SafTreeLister] (HXA-058 文件夹导入): enumerates the FILE entries of a SAF document
 * tree through the real `ContentResolver` / `DocumentsContract` children queries — the same
 * governed read path as the browse backend ([ContentResolverSafTreeReader]). Driven by the
 * picker's one-shot tree grant (`ACTION_OPEN_DOCUMENT_TREE`); no persisted grant is needed or
 * stored (an import copies the files into the app-private workspace and keeps no external
 * reference).
 *
 * Fail closed at every step: a tree URI without a parseable authority/root document id raises
 * [ScopeNotAvailable]; a children query that fails (grant revoked, provider gone, root deleted)
 * raises [SafTreeEnumerationLimit]; a walk beyond [MAX_ENTRIES_SCANNED] (files + directories)
 * raises [SafTreeEnumerationLimit] rather than silently omitting a subtree. Display names are
 * returned RAW (untrusted) — the [SafTreeImportPlanner] sanitizes them.
 */
class ContentResolverSafTreeLister(
    private val resolver: ContentResolver,
) : SafTreeLister {
    override fun listTree(treeUri: String): List<SafTreeImportEntry> {
        val parsed = Uri.parse(treeUri)
        val authority = parsed.authority ?: throw ScopeNotAvailable("SAF tree scope not available")
        val rootDocId = treeUriRootDocumentId(treeUri) ?: throw ScopeNotAvailable("SAF tree scope not available")
        val entries = mutableListOf<SafTreeImportEntry>()
        val queue = ArrayDeque<QueueItem>()
        queue.add(QueueItem(rootDocId, emptyList()))
        var scanned = 0
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            for (child in children(authority, item.docId)) {
                scanned++
                if (scanned > MAX_ENTRIES_SCANNED) throw SafTreeEnumerationLimit()
                val segments = item.segments + child.displayName
                if (child.isDirectory) {
                    queue.add(QueueItem(child.documentId, segments))
                } else {
                    entries.add(
                        SafTreeImportEntry(
                            rawSegments = segments,
                            sizeBytes = child.sizeBytes,
                            documentUri = DocumentsContract.buildDocumentUri(authority, child.documentId).toString(),
                        ),
                    )
                }
            }
        }
        return entries
    }

    /**
     * The immediate children of [parentDocId]; a failing query fails the whole enumeration. A
     * **null** cursor also fails the walk: `ContentResolver.query` returns null (it does NOT
     * throw) when no provider answers the authority — provider gone / grant revoked / root
     * deleted. Treating that as "no children" would silently return a truncated tree.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any query failure aborts the walk
    private fun children(
        authority: String,
        parentDocId: String,
    ): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, parentDocId)
        val cursor: Cursor? =
            try {
                resolver.query(uri, DOC_COLUMNS, null, null, null)
            } catch (e: Exception) {
                throw SafTreeEnumerationLimit()
            }
        if (cursor == null) throw SafTreeEnumerationLimit()
        val out = mutableListOf<Child>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                if (id != null && name != null) {
                    out.add(
                        Child(
                            documentId = id,
                            displayName = name,
                            isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            sizeBytes = if (c.isNull(3)) -1L else c.getLong(3),
                        ),
                    )
                }
            }
        }
        return out
    }

    private class QueueItem(
        val docId: String,
        val segments: List<String>,
    )

    private class Child(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
    )

    private companion object {
        /**
         * Hard bound on the whole walk (files + directories). A tree bigger than this is refused
         * as one (the import result reports a stable refusal) — a truncated walk would omit files
         * the user cannot see.
         */
        const val MAX_ENTRIES_SCANNED = 200_000

        // Fixed projection order (the rows are read by position): document id, display name,
        // MIME, size — same contract as the browse reader's rows.
        val DOC_COLUMNS: Array<String> =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
    }
}
