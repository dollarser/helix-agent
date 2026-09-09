package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object HelixMigrations {
    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE goals SET state = 'PAUSED', finishReason = NULL WHERE state = 'BLOCKED' AND " +
                        "(SELECT outcome FROM goal_runs WHERE goalId = goals.id " +
                        "ORDER BY startedAt DESC, rowid DESC LIMIT 1) = 'BLOCKED(EVIDENCE_BINDING_REQUIRED)'",
                )
            }
        }

    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE turns ADD COLUMN resultCollectedAt INTEGER")
                db.execSQL("ALTER TABLE turns ADD COLUMN pauseRequestedAt INTEGER")
                db.execSQL(
                    "UPDATE goals SET state = 'BLOCKED' WHERE state = 'PAUSED' AND " +
                        "(SELECT outcome FROM goal_runs WHERE goalId = goals.id " +
                        "ORDER BY startedAt DESC, rowid DESC LIMIT 1) LIKE 'BUDGET_EXHAUSTED(%'",
                )
            }
        }

    val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN directoryRef TEXT")
            }
        }

    /** v8 -> v9: durable usage reservations, including unknown outcomes after process death. */
    val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal_usage_reservations` (" +
                        "`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`reservedTokens` INTEGER NOT NULL, `reservedMillis` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `chargedTokens` INTEGER, `chargedMillis` INTEGER, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`runId`) REFERENCES `goal_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_goal_usage_reservations_runId` " +
                        "ON `goal_usage_reservations` (`runId`)",
                )
            }
        }

    /** v7 -> v8: explicit Goal run/Turn association; existing Turns are never guessed into Goals. */
    val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal_turn_bindings` (" +
                        "`turnId` TEXT NOT NULL, `runId` TEXT NOT NULL, PRIMARY KEY(`turnId`), " +
                        "FOREIGN KEY(`turnId`) REFERENCES `turns`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`runId`) REFERENCES `goal_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_goal_turn_bindings_runId` ON `goal_turn_bindings` (`runId`)",
                )
            }
        }

    /**
     * v1 -> v2 (HXA-034, approval hash and one-time consumption):
     *
     * - `approvals.argsHash` is renamed to `bindingHash`: from v2 it stores the full
     *   ApprovalBinding hash (tool/version/schema/scope/session/target/UI token/args),
     *   not just the argument digest;
     * - `approvals.expiresAt` bounds the approval window; every migrated row is written
     *   with `0` (fail closed: a v1 approval can never mint or consume a proof after
     *   migration).
     *
     * Renamed via copy-and-swap, NOT `ALTER TABLE ... RENAME COLUMN`: that statement
     * needs SQLite >= 3.25, which Android only ships from API 30, and `minSdk` is 29 —
     * on an API 29 (Android 10) device the column-rename form throws
     * `near "COLUMN": syntax error` and the v1 -> v2 upgrade crashes on launch.
     * The new table mirrors the canonical Room v2 DDL for [ApprovalEntity]
     * (`bindingHash` + `expiresAt INTEGER NOT NULL`) so the result is byte-identical to
     * a fresh v2 create; the `approvals` index is recreated after the rename.
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `approvals_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`toolCallId` TEXT NOT NULL, " +
                        "`bindingHash` TEXT NOT NULL, " +
                        "`decision` TEXT, " +
                        "`decidedAt` INTEGER, " +
                        "`consumedAt` INTEGER, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`toolCallId`) REFERENCES `tool_calls`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `approvals_new` " +
                        "(`id`, `toolCallId`, `bindingHash`, `decision`, `decidedAt`, " +
                        "`consumedAt`, `expiresAt`) " +
                        "SELECT `id`, `toolCallId`, `argsHash`, `decision`, `decidedAt`, " +
                        "`consumedAt`, 0 FROM `approvals`",
                )
                // Dropping the old table also drops its indexes.
                db.execSQL("DROP TABLE `approvals`")
                // Plain table rename (not column rename) is supported on every SQLite.
                db.execSQL("ALTER TABLE `approvals_new` RENAME TO `approvals`")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_approvals_toolCallId` " +
                        "ON `approvals` (`toolCallId`)",
                )
            }
        }

    /**
     * v2 -> v3 (HXA-037, structured user questions with one-time receipts, doc 11 section 4):
     * adds the `interaction_receipts` table. No existing table changes; the table is
     * additive and empty on upgrade. A receipt row is deliberately NOT foreign-keyed to
     * `tool_calls` (a question is not a tool call) and carries no approval fields —
     * answering it can never create or consume an Approval Proof.
     */
    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `interaction_receipts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`turnId` TEXT NOT NULL, " +
                        "`requestId` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, " +
                        "`questionSummary` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "`answerHash` TEXT, " +
                        "`answeredAt` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

    /**
     * v3 -> v4 (HXA-049, ADR-0014: message-attachment relation):
     * adds the `message_attachments` table — the ordered relation from a message to the
     * immutable Artifact snapshot it was bound to (`boundSha256` for fail-closed re-verification
     * on send, confirm and retry). Additive and empty on upgrade, mirroring the canonical Room v4 DDL
     * for [MessageAttachmentEntity]; both FKs (message, artifact) cascade and the
     * (messageId, ordinal) index is unique.
     */
    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_attachments` (" +
                        "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, " +
                        "`artifactId` TEXT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, " +
                        "`purpose` TEXT NOT NULL, " +
                        "`boundSha256` TEXT NOT NULL, " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`artifactId`) REFERENCES `artifacts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_attachments_messageId_ordinal` " +
                        "ON `message_attachments` (`messageId`, `ordinal`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_attachments_artifactId` " +
                        "ON `message_attachments` (`artifactId`)",
                )
            }
        }

    /**
     * v4 -> v5 (HXA-068, ADR-0005: persistent ADVANCED high-sensitivity egress rules):
     * adds the `high_sensitivity_rules` table — one row per exactly-bound, time-boxed,
     * revocable rule (stable Provider/MCP id + normalized origin + lossless user scope +
     * validity window). Additive and empty on upgrade, mirroring the canonical Room v5 DDL
     * for [HighSensitivityRuleEntity]; the table has no foreign keys (a rule is a standing
     * policy grant, not a relation to a session/turn/tool-call) and no data-category column
     * (a stored rule is always SENSITIVE — the invariant the rule's constructor enforces).
     */
    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `high_sensitivity_rules` (" +
                        "`id` TEXT NOT NULL, " +
                        "`targetKind` TEXT NOT NULL, " +
                        "`targetId` TEXT NOT NULL, " +
                        "`originFull` TEXT NOT NULL, " +
                        "`scopeEncoded` TEXT NOT NULL, " +
                        "`createdAtEpoch` INTEGER NOT NULL, " +
                        "`expiresAtEpoch` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

    /** v5 -> v6 (HXA-078): disabled A2A config plus bounded Agent Card/Skill snapshots. */
    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a2a_agents` (" +
                        "`id` TEXT NOT NULL, " +
                        "`endpointRef` TEXT NOT NULL, " +
                        "`authAlias` TEXT, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`cardHash` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a2a_capabilities` (" +
                        "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`agentId` TEXT NOT NULL, " +
                        "`interfaceUrl` TEXT NOT NULL, " +
                        "`binding` TEXT NOT NULL, " +
                        "`protocolVersion` TEXT NOT NULL, " +
                        "`skillId` TEXT NOT NULL, " +
                        "`skillHash` TEXT NOT NULL, " +
                        "`inputModes` TEXT NOT NULL, " +
                        "`outputModes` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`agentId`) REFERENCES `a2a_agents`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_capabilities_agentId_skillId` " +
                        "ON `a2a_capabilities` (`agentId`, `skillId`)",
                )
            }
        }

    /** v6 -> v7 (HXA-079): durable one-local-call-to-one-remote-Task reconciliation. */
    val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `a2a_capabilities` ADD COLUMN `tenant` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a2a_tasks` (" +
                        "`toolCallId` TEXT NOT NULL, " +
                        "`agentId` TEXT NOT NULL, " +
                        "`skillId` TEXT NOT NULL, " +
                        "`cardHash` TEXT NOT NULL, " +
                        "`skillHash` TEXT NOT NULL, " +
                        "`inputHash` TEXT NOT NULL, " +
                        "`interfaceUrl` TEXT NOT NULL, " +
                        "`binding` TEXT NOT NULL, " +
                        "`protocolVersion` TEXT NOT NULL, " +
                        "`tenant` TEXT, " +
                        "`taskId` TEXT, " +
                        "`contextId` TEXT, " +
                        "`lastEventSequence` INTEGER NOT NULL, " +
                        "`lastEventId` TEXT, " +
                        "`state` TEXT NOT NULL, " +
                        "`deliveryState` TEXT NOT NULL, " +
                        "`updatedAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`toolCallId`), " +
                        "FOREIGN KEY(`toolCallId`) REFERENCES `tool_calls`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_tasks_taskId` ON `a2a_tasks` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_tasks_state` ON `a2a_tasks` (`state`)")
            }
        }
}
