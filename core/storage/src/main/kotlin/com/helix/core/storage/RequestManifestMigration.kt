package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object RequestManifestMigration {
    val MIGRATION_27_28 =
        object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_calls ADD COLUMN requestManifest TEXT")
            }
        }
}
