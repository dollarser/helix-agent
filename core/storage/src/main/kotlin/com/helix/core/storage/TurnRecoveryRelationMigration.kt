package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v30 -> v31: records successor/predecessor identity and adds the clean-slate Turn-level review
 * command receipt. Per-call review facts remain in tool_call_reviews.
 */
internal object TurnRecoveryRelationMigration {
    val MIGRATION_30_31 =
        object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `turn_runtime_records_clean` (" +
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
                        "PRIMARY KEY(`turnId`), " +
                        "FOREIGN KEY(`turnId`) REFERENCES `turns`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `turn_runtime_records_clean` (" +
                        "turnId,version,providerId,modelId,providerSnapshot,mode,chatToolsEnabled," +
                        "budgetsJson,reasoning,goalBudgetsJson,consumedModelCalls,consumedTokens,admittedToolRounds" +
                        ") SELECT " +
                        "turnId,version,providerId,modelId,providerSnapshot,mode,chatToolsEnabled," +
                        "budgetsJson,reasoning,goalBudgetsJson,consumedModelCalls,consumedTokens,admittedToolRounds " +
                        "FROM `turn_runtime_records`",
                )
                db.execSQL("DROP TABLE `turn_runtime_records`")
                db.execSQL("ALTER TABLE `turn_runtime_records_clean` RENAME TO `turn_runtime_records`")
                db.execSQL("ALTER TABLE `turns` ADD COLUMN `recoveryFromTurnId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_turns_recoveryFromTurnId` " +
                        "ON `turns` (`recoveryFromTurnId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `turn_review_receipts` (" +
                        "`turnId` TEXT NOT NULL, " +
                        "`clientActionId` TEXT NOT NULL, " +
                        "`actionFingerprint` TEXT NOT NULL, " +
                        "PRIMARY KEY(`turnId`), " +
                        "FOREIGN KEY(`turnId`) REFERENCES `turns`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }
}
