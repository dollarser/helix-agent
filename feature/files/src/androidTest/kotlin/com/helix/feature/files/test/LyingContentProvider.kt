package com.helix.feature.files.test

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * HXA-044: the in-APK LYING ContentProvider the instrumented tests drive the REAL
 * [com.helix.feature.files.ContentResolverSaf*] adapters against (doc 07: SAF provider 谎报
 * size/MIME/display name — and 大流取消 needs a stream that never ends).
 *
 * Each case is addressed by the first path segment of `content://<authority>/<case>`:
 *
 * - `honest`           — reports the true size and streams exactly that many bytes;
 * - `under-reported`   — reports size 10, streams 100 bytes;
 * - `unknown-oversized`— reports no size, streams 2048 bytes (the pipeline's hard cap decides);
 * - `lying-mime`       — `getType` claims `image/png`, the bytes are plain text;
 * - `evil-name`        — display name with path separators and a NUL control character;
 * - `infinite`         — no size, an endless byte stream (cancellation target);
 * - `denied`           — `openFile` throws SecurityException;
 * - `granted-tree` / `denied-tree` — query succeeds / throws (撤销检测 probe targets);
 * - `export-sink`      — writable destination that HONESTLY reports its post-write size.
 */
class LyingContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        when (pathOf(uri)) {
            "lying-mime" -> "image/png"
            else -> null
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val case = pathOf(uri)
        if (case == "denied-tree") throw SecurityException("grant revoked")
        val columns = projection ?: arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { valueFor(it, case) }.toTypedArray())
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val case = pathOf(uri)
        return when (case) {
            "denied" -> {
                throw SecurityException("access denied")
            }

            "infinite" -> {
                openEndlessPipe()
            }

            "export-sink" -> {
                if (mode == "w") {
                    ParcelFileDescriptor.open(
                        sinkFile(),
                        ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                } else {
                    ParcelFileDescriptor.open(sinkFile(), ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }

            else -> {
                val file = File(context!!.filesDir, "lying-$case.bin")
                file.writeBytes(payloadFor(case))
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
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

    /** The provider's post for a column: exactly what a hostile provider would report. */
    private fun valueFor(
        column: String,
        case: String,
    ): Any? =
        when (column) {
            OpenableColumns.SIZE -> {
                when (case) {
                    "under-reported" -> 10L

                    "honest" -> payloadFor("honest").size.toLong()

                    // The honest destination: reports its true size AFTER the export wrote it.
                    "export-sink" -> sinkFile().takeIf { it.exists() }?.length()

                    else -> null
                }
            }

            OpenableColumns.DISPLAY_NAME -> {
                when (case) {
                    "evil-name" -> "../../etc/evil\u0000name"
                    "honest" -> "honest.txt"
                    else -> case
                }
            }

            else -> {
                null
            }
        }

    /** A byte stream that never reaches EOF: a writer thread fills the pipe until the reader
     *  gives up (the test's cancel path closes the read side, which kills the writer). */
    @Suppress("SwallowedException") // the reader closing (cancel or test end) is the expected end
    private fun openEndlessPipe(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val writeSide = pipe[1]
        val writer =
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        val chunk = ByteArray(4096)
                        while (true) out.write(chunk)
                    }
                } catch (e: Exception) {
                    // The reader closed (cancel or test end): the pipe write fails and the
                    // writer is done. Nothing to report.
                }
            }
        writer.isDaemon = true
        writer.start()
        return pipe[0]
    }

    private fun sinkFile() = File(context!!.filesDir, "export-sink.bin")

    private fun payloadFor(case: String): ByteArray =
        when (case) {
            "under-reported" -> ByteArray(100) { it.toByte() }
            "unknown-oversized" -> ByteArray(2048)
            "lying-mime" -> "this is plain text, not a png\n".toByteArray()
            "honest" -> "honest document body\n".toByteArray()
            else -> ByteArray(16)
        }

    private fun pathOf(uri: Uri): String = uri.pathSegments.firstOrNull().orEmpty()

    companion object {
        const val AUTHORITY = "com.helix.feature.files.test"

        /** The evil display name: separators plus a NUL (doc 07). */
        const val EVIL_NAME = "../../etc/evil\u0000name"
    }
}
