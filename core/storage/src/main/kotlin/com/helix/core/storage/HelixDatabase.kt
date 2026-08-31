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
import com.helix.core.storage.dao.McpCapabilityDao
import com.helix.core.storage.dao.McpServerDao
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
import com.helix.core.storage.entity.McpCapabilityEntity
import com.helix.core.storage.entity.McpServerEntity
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
 * Helix local database (architecture doc 9). Schema version 2 (HXA-034) holds all base tables
 * plus the plan/goal tables of doc section 9.1 (v1, HXA-014):
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
            TurnEntity::class,
            ModelCallEntity::class,
            ToolCallEntity::class,
            ToolResultEntity::class,
            ApprovalEntity::class,
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
        ],
    version = 2,
    exportSchema = true,
)
@Suppress("TooManyFunctions") // Room @Database requires one accessor per DAO of the 22 doc 9.1 tables
abstract class HelixDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun turnDao(): TurnDao

    abstract fun modelCallDao(): ModelCallDao

    abstract fun toolCallDao(): ToolCallDao

    abstract fun toolResultDao(): ToolResultDao

    abstract fun approvalDao(): ApprovalDao

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

    companion object {
        const val DATABASE_NAME = "helix.db"

        /**
         * v1 -> v2 (HXA-034, approval hash and one-time consumption):
         *
         * - `approvals.argsHash` is renamed to `bindingHash`: from v2 it stores the full
         *   ApprovalBinding hash (tool/version/schema/scope/session/target/UI token/args),
         *   not just the argument digest;
         * - `approvals.expiresAt` bounds the approval window; the NOT NULL default 0 marks
         *   every migrated row already expired (fail closed: a v1 approval can never mint or
         *   consume a proof after migration).
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE approvals RENAME COLUMN argsHash TO bindingHash")
                    db.execSQL("ALTER TABLE approvals ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
                }
            }
    }
}
