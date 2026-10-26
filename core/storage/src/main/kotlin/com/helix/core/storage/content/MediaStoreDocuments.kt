package com.helix.core.storage.content

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.helix.core.workspace.AtomicFileWriter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Properties

/** Fixed shared Downloads placement. Pending rows + a private recovery receipt preserve prior content. */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught") // One platform storage/publication boundary.
class MediaStoreDocuments(
    context: Context,
    namespace: String,
) : DocumentStorage,
    DocumentDraftRecovery,
    DocumentShareAccess {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val owner = context.packageName
    private val root: String
    private val receipt: java.nio.file.Path
    private val namespaceLock: Any
    private val conflicts get() = receipt.resolveSibling(receipt.fileName.toString() + ".drafts")

    init {
        require(namespace.matches(Regex("[A-Za-z0-9_.-]{1,160}")) && namespace !in setOf(".", ".."))
        root = "Documents/Helix/$namespace/"
        receipt = java.io.File(context.noBackupFilesDir, "document-publication/$namespace.properties").toPath()
        namespaceLock = locks.computeIfAbsent(receipt) { Any() }
    }

    @Synchronized
    override fun list(directory: String): List<DocumentStorage.File> {
        var total = 0L
        return references(directory).mapNotNull { id ->
            val file =
                try {
                    read(id, MAX_TEXT_BYTES)
                } catch (missing: java.io.FileNotFoundException) {
                    if (exists(uri(id))) throw missing
                    null
                }
            file?.also {
                total += it.bytes.size
                check(total <= MAX_CATALOG_BYTES) { "DOCUMENT_CATALOG_SIZE_LIMIT" }
            }
        }
    }

    @Synchronized
    override fun references(directory: String): List<String> =
        operation {
            recover()
            val relative = directory(directory)
            val unpublished = draftRecords().map { it.second.getProperty("new") }.toSet()
            requireNotNull(
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                        "${MediaStore.MediaColumns.IS_PENDING} = 0 AND " +
                        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf(relative, owner),
                    "${MediaStore.MediaColumns._ID} ASC",
                ),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(size < MAX_FILES) { "DOCUMENT_CATALOG_LIMIT" }
                        val id = cursor.getLong(0).toString()
                        if (id !in unpublished) add(id)
                    }
                }
            }
        }

    @Synchronized
    override fun read(
        reference: String,
        maxBytes: Int,
    ): DocumentStorage.File {
        require(maxBytes in 1..MAX_FILE_BYTES)
        val uri = uri(reference)
        val name = metadata(uri).first
        val bytes =
            requireNotNull(resolver.openInputStream(uri)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= maxBytes) { "DOCUMENT_SIZE_LIMIT" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        return DocumentStorage.File(reference, name, bytes, DocumentStorage.hash(bytes))
    }

    @Synchronized
    override fun save(
        directory: String,
        name: String,
        bytes: ByteArray,
        previous: DocumentStorage.File?,
        mimeType: String,
    ): DocumentStorage.File =
        operation {
            recover()
            val values = values(directory, name, bytes.size, mimeType)
            previous?.let {
                check(read(it.reference, MAX_FILE_BYTES).hash == it.hash) { "DOCUMENT_CONFLICT_RELOAD" }
            }
            val target = requireNotNull(resolver.insert(collection, values)) { "DOCUMENT_CREATE_FAILED" }
            val reference = ContentUris.parseId(target).toString()
            var prepared = false
            try {
                requireNotNull(resolver.openOutputStream(target, "w")).use { it.write(bytes) }
                val written = read(reference, MAX_FILE_BYTES)
                check(written.hash == DocumentStorage.hash(bytes)) { "DOCUMENT_WRITE_UNVERIFIED" }
                val record =
                    Properties().apply {
                        setProperty("format", "1")
                        setProperty("new", reference)
                        setProperty("hash", written.hash)
                        setProperty("old", previous?.reference.orEmpty())
                        setProperty("oldHash", previous?.hash.orEmpty())
                    }
                Files.createDirectories(receipt.parent)
                val output = ByteArrayOutputStream().also { record.store(it, null) }
                AtomicFileWriter.writeAtomic(receipt, output.toByteArray())
                prepared = true
                recover()
                check(draftRecords().none { it.second.getProperty("new") == reference }) { "DOCUMENT_CONFLICT_RELOAD" }
                read(reference, MAX_FILE_BYTES)
            } catch (failure: Exception) {
                if (!prepared) {
                    try {
                        if (metadata(target).second) resolver.delete(target, null, null)
                    } catch (cleanup: Exception) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }

    private fun values(
        directory: String,
        name: String,
        size: Int,
        mimeType: String,
    ): ContentValues {
        require(
            name.isNotBlank() && name.length <= 200 && name.none { it == '/' || it == '\\' || it.isISOControl() },
        )
        require(size <= MAX_FILE_BYTES)
        val validMime = mimeType.length <= 200 && mimeType.matches(MIME_TYPE)
        require(validMime)
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory(directory))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    @Synchronized
    override fun delete(previous: DocumentStorage.File) =
        operation {
            recover()
            check(read(previous.reference, MAX_FILE_BYTES).hash == previous.hash) { "DOCUMENT_CONFLICT_RELOAD" }
            check(resolver.delete(uri(previous.reference), null, null) == 1) { "DOCUMENT_DELETE_UNCONFIRMED" }
        }

    /** Reconciles this original publication only; edited old/new documents are retained, never guessed away. */
    private fun recover() {
        if (!Files.exists(receipt)) return
        val record = Properties().apply { Files.newInputStream(receipt).use { load(it) } }
        check(record.getProperty("format") == "1")
        val next = record.getProperty("new")
        val nextFile = read(next, MAX_FILE_BYTES)
        if (nextFile.hash != record.getProperty("hash")) {
            retainDraft()
            return
        }
        reconcile(record, nextFile)
    }

    private fun reconcile(
        record: Properties,
        nextFile: DocumentStorage.File,
    ) {
        val next = nextFile.reference
        val old = record.getProperty("old")
        val pending = metadata(uri(next)).second
        val previous = if (old.isNotEmpty() && exists(uri(old))) read(old, MAX_FILE_BYTES) else null
        val changed = previous != null && previous.hash != record.getProperty("oldHash")
        val missing = pending && old.isNotEmpty() && previous == null
        if (changed || missing) {
            retainDraft()
            return
        }
        if (pending) {
            check(
                resolver.update(
                    uri(next),
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1,
            ) { "DOCUMENT_PUBLICATION_UNCONFIRMED" }
        }
        check(!metadata(uri(next)).second && read(next, MAX_FILE_BYTES).hash == nextFile.hash)
        if (previous != null) {
            if (read(previous.reference, MAX_FILE_BYTES).hash != previous.hash) {
                retainDraft()
                return
            }
            check(resolver.delete(uri(old), null, null) == 1) { "DOCUMENT_REPLACEMENT_UNCONFIRMED" }
        }
        Files.delete(receipt)
    }

    private fun retainDraft() {
        Files.createDirectories(conflicts)
        check(draftRecords().size < 128) { "DOCUMENT_RECOVERY_LIMIT" }
        Files.move(
            receipt,
            conflicts.resolve(
                java.util.UUID
                    .randomUUID()
                    .toString() + ".properties",
            ),
        )
    }

    private fun draftRecords(): List<Pair<String, Properties>> {
        if (!Files.exists(conflicts)) return emptyList()
        return Files.newDirectoryStream(conflicts, "*.properties").use { entries ->
            entries
                .map { path ->
                    val token = path.fileName.toString().removeSuffix(".properties")
                    check(
                        java.util.UUID
                            .fromString(token)
                            .toString() == token,
                    )
                    token to Properties().apply { Files.newInputStream(path).use { load(it) } }
                }.also { check(it.size <= 128) }
        }
    }

    override fun drafts(): List<DocumentDraftRecovery.Draft> =
        operation {
            recover()
            draftRecords().map { (token, record) ->
                val file = read(record.getProperty("new"), MAX_FILE_BYTES)
                check(file.hash == record.getProperty("hash")) { "DOCUMENT_RECOVERY_CONFLICT" }
                DocumentDraftRecovery.Draft(token, file)
            }
        }

    override fun completeDraft(
        token: String,
        expectedHash: String,
    ) = operation {
        val record = draftRecords().single { it.first == token }.second
        val file = read(record.getProperty("new"), MAX_FILE_BYTES)
        check(file.hash == expectedHash && file.hash == record.getProperty("hash")) { "DOCUMENT_RECOVERY_CONFLICT" }
        check(
            resolver.update(
                uri(file.reference),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory("recovered"))
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            ) == 1,
        ) { "DOCUMENT_RECOVERY_UNCONFIRMED" }
        check(read(file.reference, MAX_FILE_BYTES).hash == file.hash)
        Files.delete(conflicts.resolve("$token.properties"))
    }

    private fun metadata(uri: Uri): Pair<String, Boolean> =
        requireNotNull(
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                ),
                null,
                null,
                null,
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw java.io.FileNotFoundException("Document is unavailable")
            check(cursor.getString(1).startsWith(root)) { "DOCUMENT_OUTSIDE_LIBRARY" }
            check(cursor.getString(3) == owner) { "DOCUMENT_REQUIRES_ADOPTION" }
            cursor.getString(0) to (cursor.getInt(2) != 0)
        }

    private fun exists(uri: Uri): Boolean =
        requireNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)).use {
            it.moveToFirst()
        }

    override fun shareReference(reference: String): String = uri(reference).also { metadata(it) }.toString()

    private fun uri(reference: String): Uri {
        require(reference.matches(Regex("[1-9][0-9]{0,18}")))
        return ContentUris.withAppendedId(collection, reference.toLong())
    }

    private fun directory(value: String): String {
        require(value.split('/').all { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) })
        return "$root$value/"
    }

    private fun <T> operation(action: () -> T): T =
        synchronized(namespaceLock) {
            Files.createDirectories(receipt.parent)
            java.nio.channels.FileChannel
                .open(
                    receipt.resolveSibling(receipt.fileName.toString() + ".lock"),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                ).use { channel -> channel.lock().use { action() } }
        }

    companion object {
        private val MIME_TYPE = Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")
        private val locks = java.util.concurrent.ConcurrentHashMap<java.nio.file.Path, Any>()
        private const val MAX_FILES = 4096
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private const val MAX_CATALOG_BYTES = 32 * 1024 * 1024
    }
}
