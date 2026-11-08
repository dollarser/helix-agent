package com.helix.app.documents

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Private in-process Android provider. The open gate deliberately ignores thread interruption. */
internal class BlockingSnapshotProvider(
    private val source: File,
) : ContentProvider() {
    val entered = AtomicInteger()
    val active = AtomicInteger()
    val interrupted = AtomicInteger()

    @Volatile private var gate = CountDownLatch(0)

    fun hold() {
        check(active.get() == 0)
        gate = CountDownLatch(1)
    }

    fun release() = gate.countDown()

    override fun onCreate(): Boolean = true

    @Suppress("SwallowedException") // A non-cooperative provider is the explicit failure fixture.
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        check(DocumentsContract.getDocumentId(uri) == "root/report.pdf" && mode == "r")
        val current = gate
        active.incrementAndGet()
        entered.incrementAndGet()
        try {
            while (current.count != 0L) {
                try {
                    current.await()
                } catch (_: InterruptedException) {
                    interrupted.incrementAndGet()
                }
            }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = requireNotNull(projection)
        val document = DocumentsContract.getDocumentId(uri)
        val directory = document == "root" && uri.lastPathSegment != "children"
        check(document in setOf("root", "root/report.pdf"))
        return MatrixCursor(columns).apply {
            addRow(
                columns
                    .map { column ->
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> if (directory) "root" else "root/report.pdf"
                            Document.COLUMN_DISPLAY_NAME -> if (directory) "fixture" else "report.pdf"
                            Document.COLUMN_MIME_TYPE -> if (directory) Document.MIME_TYPE_DIR else "application/pdf"
                            Document.COLUMN_SIZE -> if (directory) 0L else source.length()
                            Document.COLUMN_FLAGS -> 0
                            else -> null
                        }
                    }.toTypedArray<Any?>(),
            )
        }
    }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri = error("Read-only fixture")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = error("Read-only fixture")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = error("Read-only fixture")
}
