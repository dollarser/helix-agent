package com.helix.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A single foreground confirmation; neither opening the database nor restarting grants consent. */
class PreviewDatabaseResetRequired(
    private val verify: () -> Unit = {},
    private val reset: (() -> Unit) -> Unit,
) : IllegalStateException("The previous preview database requires user-confirmed reset") {
    private val consumed = AtomicBoolean()

    fun verifyUnchanged() = verify()

    fun confirm(beforeDelete: () -> Unit = {}) {
        check(consumed.compareAndSet(false, true)) { "Database reset confirmation was already consumed" }
        reset(beforeDelete)
    }
}

/** Read-only current Room identity check; no historical migration chain. */
internal object PreviewDatabaseGate {
    private val lock = Any()
    private var currentSchema: Schema? = null

    fun requireCompatible(
        context: Context,
        name: String,
    ) = synchronized(lock) {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return@synchronized
        val schema = readSchema(file)
        if (schema == expectedSchema(context)) return@synchronized
        val observed = files(file)
        val verify = {
            synchronized(lock) {
                check(readSchema(file) == schema && files(file) == observed) {
                    "Database changed since the reset warning; reopen Helix"
                }
            }
        }
        throw PreviewDatabaseResetRequired(verify) { beforeDelete ->
            synchronized(lock) {
                check(readSchema(file) == schema && files(file) == observed) {
                    "Database changed since the reset warning; reopen Helix"
                }
                beforeDelete()
                check(readSchema(file) == schema && files(file) == observed) {
                    "Database changed during reset preparation; reopen Helix"
                }
                check(context.deleteDatabase(name)) { "The confirmed database reset could not be completed" }
            }
        }
    }

    private fun expectedSchema(context: Context): Schema =
        currentSchema ?: Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build().let { database ->
            try {
                val db = database.openHelper.writableDatabase
                val identity =
                    db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                        check(it.moveToFirst()) { "Current Room schema identity is missing" }
                        it.getString(0)
                    }
                Schema(db.version, identity).also { currentSchema = it }
            } finally {
                database.close()
            }
        }

    private fun readSchema(file: File): Schema =
        SQLiteDatabase
            .openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) {
                // Android's default corruption handler deletes files. Read failure is not reset consent.
                throw SQLiteDatabaseCorruptException("Existing preview database could not be read")
            }.use { db ->
                val hasIdentity =
                    db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'room_master_table'", null).use {
                        it.moveToFirst()
                    }
                val identity = if (hasIdentity) identity(db) else null
                Schema(db.version, identity)
            }

    private fun identity(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun files(database: File): List<FileStamp?> =
        listOf(database, File(database.path + "-wal")).map { file ->
            if (!file.exists() || (file != database && file.length() == 0L)) {
                null
            } else {
                val attributes =
                    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(attributes.isRegularFile) { "Database must be a regular private file" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                FileStamp(attributes.fileKey()?.toString(), attributes.size(), digest.digest().toList())
            }
        }

    private data class Schema(
        val version: Int,
        val identity: String?,
    )

    private data class FileStamp(
        val key: String?,
        val size: Long,
        val digest: List<Byte>,
    )
}
