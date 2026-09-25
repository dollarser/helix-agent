package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v29 -> v30 (Wave 1 E1-A0): adds the one-to-one durable TurnEngine runtime snapshot/checkpoint.
 *
 * Additive and intentionally empty on upgrade. This is the historical v30 schema: the review
 * receipt columns existed here before the clean-slate successor-Turn cutover. v30 -> v31 removes
 * those columns; current production code never reads them.
 */
internal object TurnRuntimeMigration {
    val MIGRATION_29_30 =
        object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `turn_runtime_records` (" +
                        "`turnId` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, " +
                        "`providerId` TEXT NOT NULL, " +
                        "`modelId` TEXT NOT NULL, " +
                        "`providerSnapshot` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`chatToolsEnabled` INTEGER NOT NULL, " +
                        "`budgetsJson` TEXT NOT NULL, " +
                        "`reasoning` TEXT NOT NULL, " +
                        "`goalBudgetsJson` TEXT NOT NULL, " +
                        "`consumedModelCalls` INTEGER NOT NULL, " +
                        "`consumedTokens` INTEGER NOT NULL, " +
                        "`admittedToolRounds` INTEGER NOT NULL, " +
                        "`reviewActionId` TEXT, " +
                        "`reviewActionFingerprint` TEXT, " +
                        "`reviewOutcome` TEXT, " +
                        "`reviewModelCallId` TEXT, " +
                        "PRIMARY KEY(`turnId`), " +
                        "FOREIGN KEY(`turnId`) REFERENCES `turns`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }
}
