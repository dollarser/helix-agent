package com.helix.app.knowledge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.workspace.AtomicFileWriter
import java.util.Properties

/** Deletes only a UUID-named synthetic collection in the independent validation package. */
internal fun clearKnowledgeDocuments(
    context: Context,
    name: String,
) {
    check(context.packageName.endsWith(".asrvalidation"))
    require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}")))
    require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").containsMatchIn(name))
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val root = "Documents/Helix/$name/"
    val ids =
        requireNotNull(
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$root%"),
                null,
            ),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
    ids.forEach { context.contentResolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
    java.io.File(context.filesDir, "agent-library-history/$name").deleteRecursively()
    java.io.File(context.noBackupFilesDir, "document-publication/$name.properties").delete()
    java.io.File(context.noBackupFilesDir, "document-publication/$name.properties.drafts").deleteRecursively()
    java.io.File(context.noBackupFilesDir, "document-publication/$name.properties.lock").delete()
}

internal fun prepareKnowledgeSave(
    context: Context,
    name: String,
    old: DocumentStorage.File,
    body: String,
): Long {
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri =
        context.contentResolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "note.md")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Helix/$name/notes/")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
    val bytes =
        old.bytes
            .toString(Charsets.UTF_8)
            .replace("\nold", "\n$body")
            .toByteArray()
    context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
    val id = ContentUris.parseId(uri)
    val record =
        Properties().apply {
            setProperty("format", "1")
            setProperty("new", id.toString())
            setProperty("hash", DocumentStorage.hash(bytes))
            setProperty("old", old.reference)
            setProperty("oldHash", old.hash)
        }
    val receipt = java.io.File(context.noBackupFilesDir, "document-publication/$name.properties").toPath()
    java.nio.file.Files
        .createDirectories(receipt.parent)
    val bytesOut =
        java.io
            .ByteArrayOutputStream()
            .also { record.store(it, null) }
            .toByteArray()
    AtomicFileWriter.writeAtomic(receipt, bytesOut)
    return id
}
