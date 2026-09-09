package com.helix.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.helix.core.storage.dao.A2aAgentDao
import com.helix.core.storage.dao.A2aCapabilityDao
import com.helix.core.storage.dao.A2aTaskDao
import com.helix.core.storage.dao.ApprovalDao
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.dao.AuditEventDao
import com.helix.core.storage.dao.CapabilityGrantDao
import com.helix.core.storage.dao.ExecutionDao
import com.helix.core.storage.dao.ExecutionTargetDao
import com.helix.core.storage.dao.GoalDao
import com.helix.core.storage.dao.GoalRunDao
import com.helix.core.storage.dao.GoalTurnBindingDao
import com.helix.core.storage.dao.GoalUsageReservationDao
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
import com.helix.core.storage.entity.A2aAgentEntity
import com.helix.core.storage.entity.A2aCapabilityEntity
import com.helix.core.storage.entity.A2aTaskEntity
import com.helix.core.storage.entity.ApprovalEntity
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.entity.CapabilityGrantEntity
import com.helix.core.storage.entity.ExecutionEntity
import com.helix.core.storage.entity.ExecutionTargetEntity
import com.helix.core.storage.entity.GoalEntity
import com.helix.core.storage.entity.GoalRunEntity
import com.helix.core.storage.entity.GoalTurnBindingEntity
import com.helix.core.storage.entity.GoalUsageReservationEntity
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
 * ADVANCED high-sensitivity egress-rule table (v5, ADR-0005), and A2A Agent/Card snapshot
 * tables (v6, HXA-078), durable A2A task correlation (v7, HXA-079), Goal/Turn associations (v8, HXA-102),
 * and usage reservations (v9, HXA-102):
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
            GoalTurnBindingEntity::class,
            GoalUsageReservationEntity::class,
            McpServerEntity::class,
            McpCapabilityEntity::class,
            SkillEntity::class,
            SkillSnapshotEntity::class,
            CapabilityGrantEntity::class,
            ExecutionTargetEntity::class,
            HighSensitivityRuleEntity::class,
            A2aAgentEntity::class,
            A2aCapabilityEntity::class,
            A2aTaskEntity::class,
        ],
    version = 12,
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

    abstract fun goalUsageReservationDao(): GoalUsageReservationDao

    abstract fun goalTurnBindingDao(): GoalTurnBindingDao

    abstract fun mcpServerDao(): McpServerDao

    abstract fun mcpCapabilityDao(): McpCapabilityDao

    abstract fun skillDao(): SkillDao

    abstract fun skillSnapshotDao(): SkillSnapshotDao

    abstract fun highSensitivityRuleDao(): HighSensitivityRuleDao

    abstract fun a2aAgentDao(): A2aAgentDao

    abstract fun a2aCapabilityDao(): A2aCapabilityDao

    abstract fun a2aTaskDao(): A2aTaskDao

    companion object {
        const val DATABASE_NAME = "helix.db"

        val MIGRATION_11_12 = HelixMigrations.MIGRATION_11_12

        val MIGRATION_10_11 = HelixMigrations.MIGRATION_10_11

        val MIGRATION_9_10 = HelixMigrations.MIGRATION_9_10

        val MIGRATION_8_9 = HelixMigrations.MIGRATION_8_9

        val MIGRATION_7_8 = HelixMigrations.MIGRATION_7_8

        val MIGRATION_1_2 = HelixMigrations.MIGRATION_1_2

        val MIGRATION_2_3 = HelixMigrations.MIGRATION_2_3

        val MIGRATION_3_4 = HelixMigrations.MIGRATION_3_4

        val MIGRATION_4_5 = HelixMigrations.MIGRATION_4_5

        val MIGRATION_5_6 = HelixMigrations.MIGRATION_5_6

        val MIGRATION_6_7 = HelixMigrations.MIGRATION_6_7
    }
}
