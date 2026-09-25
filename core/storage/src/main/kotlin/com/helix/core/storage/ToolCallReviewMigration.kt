package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v28 -> v29 (ADR-AGENT-001, Wave 1 E0-R1): adds `tool_call_reviews` table for persistent
 * human review decisions on uncertain tool calls.
 *
 * Additive and empty on upgrade: existing tool_calls have no review records (they remain unresolved).
 * Foreign key references `tool_calls` with ON DELETE CASCADE.
 */
internal object ToolCallReviewMigration {
    val MIGRATION_28_29 =
        object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tool_call_reviews` (" +
                        "`toolCallId` TEXT NOT NULL, " +
                        "`decision` TEXT NOT NULL, " +
                        "`reviewedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`toolCallId`), " +
                        "FOREIGN KEY(`toolCallId`) REFERENCES `tool_calls`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }
}
