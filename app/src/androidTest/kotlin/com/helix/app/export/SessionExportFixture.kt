package com.helix.app.export

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.HelixStorage
import java.io.File
import java.util.UUID

/** The test provider owner grants synthetic documents; export runs with ordinary application permissions. */
internal class SessionExportFixture(
    val id: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "session-export-test-$id"
    private val bodies = File(context.filesDir, "session-export-test-$id")
    val storage = HelixStorage.open(context, databaseName, bodies)
    val service = SessionExportService(context, storage.sessionExports, "fixture")
    private val authority = InstrumentationRegistry.getInstrumentation().context.packageName + ".sessionexport"
    private val control = authority + "control"
    private val owned = mutableListOf<Uri>()

    fun seed(messages: Int = 1) {
        storage.sessions.create("selected", "synthetic export", null, null, 1)
        repeat(messages) { index ->
            storage.messages.append(
                "message-$index",
                "selected",
                null,
                "USER",
                "TEXT",
                "message $index " + "x".repeat(1024),
            )
        }
    }

    fun create(kind: String): Uri {
        val options =
            Bundle().apply {
                putString("authority", authority)
                putString("package", context.packageName)
            }
        val result = requireNotNull(context.contentResolver.call(control, "fixture-create", kind, options))
        return Uri.parse(requireNotNull(result.getString("uri"))).also(owned::add)
    }

    fun exists(uri: Uri): Boolean =
        requireNotNull(
            context.contentResolver.call(control, "fixture-exists", DocumentsContract.getDocumentId(uri), null),
        ).getBoolean("exists")

    fun opens(uri: Uri): Int =
        requireNotNull(
            context.contentResolver.call(control, "fixture-opens", DocumentsContract.getDocumentId(uri), null),
        ).getInt("count")

    fun failClose(uri: Uri) {
        context.contentResolver.call(control, "fixture-fail-close", DocumentsContract.getDocumentId(uri), null)
    }

    fun cancellations(uri: Uri): Int =
        requireNotNull(
            context.contentResolver.call(control, "fixture-cancellations", DocumentsContract.getDocumentId(uri), null),
        ).getInt("count")

    fun tail(uri: Uri): Bundle =
        requireNotNull(
            context.contentResolver.call(control, "fixture-tail", DocumentsContract.getDocumentId(uri), null),
        )

    fun read(uri: Uri): String =
        requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use {
            it.readText()
        }

    fun temporaryIsEmpty(): Boolean = File(context.cacheDir, "session-exports").listFiles().orEmpty().isEmpty()

    fun own(uri: Uri) {
        owned.add(uri)
    }

    override fun close() {
        owned.forEach { if (exists(it)) DocumentsContract.deleteDocument(context.contentResolver, it) }
        storage.close()
        context.deleteDatabase(databaseName)
        bodies.deleteRecursively()
    }
}
