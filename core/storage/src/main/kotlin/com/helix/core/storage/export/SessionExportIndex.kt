package com.helix.core.storage.export

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Disposable membership index of the fixed snapshot, never a second source of execution facts. */
internal class SessionExportIndex private constructor(
    private val file: File,
    private val database: SQLiteDatabase,
) : AutoCloseable {
    fun contains(recordId: String): Boolean =
        database.rawQuery("SELECT 1 FROM records WHERE id = ?", arrayOf(recordId)).use { it.moveToFirst() }

    override fun close() {
        try {
            database.close()
        } finally {
            check(!file.exists() || SQLiteDatabase.deleteDatabase(file)) { "Could not remove export index" }
        }
    }

    companion object {
        fun build(
            snapshot: SessionExportSnapshot,
            checkCancelled: () -> Unit,
        ): SessionExportIndex {
            val file = File.createTempFile("session-export-", ".index", snapshot.file.parentFile)
            var database: SQLiteDatabase? = null
            var successful = false
            try {
                database = SQLiteDatabase.openOrCreateDatabase(file, null)
                database.rawQuery("PRAGMA journal_mode=OFF", null).use { check(it.moveToFirst()) }
                database.execSQL("PRAGMA synchronous=OFF")
                database.execSQL("PRAGMA cache_size=-1024")
                database.execSQL("PRAGMA temp_store=FILE")
                val available = SessionExportFormat.FILE_BYTES - snapshot.file.length()
                val maximum = available / database.pageSize * database.pageSize
                require(maximum >= database.pageSize * 4) { "Export relation staging exceeds byte limit" }
                check(database.setMaximumSize(maximum) <= available)
                database.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY NOT NULL) WITHOUT ROWID")
                database.beginTransaction()
                try {
                    database.compileStatement("INSERT INTO records(id) VALUES (?)").use { insert ->
                        SessionExportJson.rows(snapshot.file, checkCancelled) { row ->
                            insert.bindString(1, row.getValue("recordId").jsonPrimitive.content)
                            insert.executeInsert()
                        }
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                checkCancelled()
                check(file.length() <= available) { "Export relation staging exceeds byte limit" }
                successful = true
                return SessionExportIndex(file, database)
            } finally {
                if (!successful) {
                    database?.close()
                    check(!file.exists() || SQLiteDatabase.deleteDatabase(file)) { "Could not remove export index" }
                }
            }
        }
    }
}
