package com.helix.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.helix.core.storage.dao.ApprovalDao
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.dao.AuditEventDao
import com.helix.core.storage.dao.CapabilityGrantDao
import com.helix.core.storage.dao.ExecutionDao
import com.helix.core.storage.dao.ExecutionTargetDao
import com.helix.core.storage.dao.GoalDao
import com.helix.core.storage.dao.GoalRunDao
import com.helix.core.storage.dao.HighSensitivityRuleDao
import com.helix.core.storage.dao.InteractionReceiptDao
import com.helix.core.storage.dao.McpCapabilityDao
import com.helix.core.storage.dao.McpServerDao
import com.helix.core.storage.dao.MessageAttachmentDao
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.dao.ModelCallDao
import com.helix.core.storage.dao.PlanDao
import com.helix.core.storage.dao.ProviderConfigDao
import com.helix.core.storage.dao.RuntimeInstallDao
import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.dao.SkillDao
import com.helix.core.storage.dao.SkillSnapshotDao
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.dao.ToolResultDao
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.ApprovalEntity
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.entity.CapabilityGrantEntity
import com.helix.core.storage.entity.ExecutionEntity
import com.helix.core.storage.entity.ExecutionTargetEntity
import com.helix.core.storage.entity.GoalEntity
import com.helix.core.storage.entity.GoalRunEntity
import com.helix.core.storage.entity.HighSensitivityRuleEntity
import com.helix.core.storage.entity.InteractionReceiptEntity
import com.helix.core.storage.entity.McpCapabilityEntity
import com.helix.core.storage.entity.McpServerEntity
import com.helix.core.storage.entity.MessageAttachmentEntity
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.entity.PlanStepEntity
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.RuntimeInstallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.SkillEntity
import com.helix.core.storage.entity.SkillSnapshotEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity

/**
 * Helix local database (architecture doc 9). Schema version 5 (HXA-068) holds all base tables
 * plus the plan/goal tables of doc section 9.1 (v1, HXA-014), the structured-question receipt
 * table (v3, doc 11 section 4), the message-attachment relation (v4, ADR-0014), and the
 * ADVANCED high-sensitivity egress-rule table (v5, ADR-0005):
 *
 * - foreign keys are declared on every relation and enforced (Room enables
 *   `PRAGMA foreign_keys = ON` for schemas that use them; the migration fixture asserts it);
 * - schema export is enabled and the committed export lives in
 *   `src/androidTest/assets/com.helix.core.storage.HelixDatabase/` (Room 2.8
 *   `<databaseFqn>/<version>.json` convention) as the migration fixture for this and future
 *   migrations (doc 9.2: migrations require a schema export plus an instrumentation test);
 * - secrets never enter the schema: `provider_configs` and `mcp_servers` store alias fields
 *   only; large bodies live in files and rows store `ContentRef` references.
 */
@Database(
    entities =
        [
            SessionEntity::class,
            MessageEntity::class,
            MessageAttachmentEntity::class,
            TurnEntity::class,
            ModelCallEntity::class,
            ToolCallEntity::class,
            ToolResultEntity::class,
            ApprovalEntity::class,
            InteractionReceiptEntity::class,
            ExecutionEntity::class,
            ArtifactEntity::class,
            AuditEventEntity::class,
            ProviderConfigEntity::class,
            RuntimeInstallEntity::class,
            PlanEntity::class,
            PlanStepEntity::class,
            GoalEntity::class,
            GoalRunEntity::class,
            McpServerEntity::class,
            McpCapabilityEntity::class,
            SkillEntity::class,
            SkillSnapshotEntity::class,
            CapabilityGrantEntity::class,
            ExecutionTargetEntity::class,
            HighSensitivityRuleEntity::class,
        ],
    version = 5,
    exportSchema = true,
)
@Suppress("TooManyFunctions") // Room @Database requires one accessor per DAO of the 24 doc 9.1 tables
abstract class HelixDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun messageAttachmentDao(): MessageAttachmentDao

    abstract fun turnDao(): TurnDao

    abstract fun modelCallDao(): ModelCallDao

    abstract fun toolCallDao(): ToolCallDao

    abstract fun toolResultDao(): ToolResultDao

    abstract fun approvalDao(): ApprovalDao

    abstract fun interactionReceiptDao(): InteractionReceiptDao

    abstract fun executionDao(): ExecutionDao

    abstract fun artifactDao(): ArtifactDao

    abstract fun auditEventDao(): AuditEventDao

    abstract fun providerConfigDao(): ProviderConfigDao

    abstract fun runtimeInstallDao(): RuntimeInstallDao

    abstract fun executionTargetDao(): ExecutionTargetDao

    abstract fun capabilityGrantDao(): CapabilityGrantDao

    abstract fun planDao(): PlanDao

    abstract fun goalDao(): GoalDao

    abstract fun goalRunDao(): GoalRunDao

    abstract fun mcpServerDao(): McpServerDao

    abstract fun mcpCapabilityDao(): McpCapabilityDao

    abstract fun skillDao(): SkillDao

    abstract fun skillSnapshotDao(): SkillSnapshotDao

    abstract fun highSensitivityRuleDao(): HighSensitivityRuleDao

    companion object {
        const val DATABASE_NAME = "helix.db"

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
    }
}
