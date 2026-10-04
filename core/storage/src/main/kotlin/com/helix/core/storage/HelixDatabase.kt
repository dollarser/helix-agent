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
import com.helix.core.storage.dao.MessageReferenceSnapshotDao
import com.helix.core.storage.dao.ModelCallDao
import com.helix.core.storage.dao.PlanDao
import com.helix.core.storage.dao.ProviderConfigDao
import com.helix.core.storage.dao.RuntimeInstallDao
import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.dao.SessionExpertDao
import com.helix.core.storage.dao.SessionPermissionConfigDao
import com.helix.core.storage.dao.SessionPermissionDefaultsDao
import com.helix.core.storage.dao.SessionPermissionDraftDao
import com.helix.core.storage.dao.SkillDao
import com.helix.core.storage.dao.SkillSnapshotDao
import com.helix.core.storage.dao.ToolAvailabilityDao
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.dao.ToolCallReviewDao
import com.helix.core.storage.dao.ToolResultDao
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.dao.TurnReviewReceiptDao
import com.helix.core.storage.dao.TurnRuntimeRecordDao
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
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.entity.PlanStepEntity
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.RuntimeInstallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.SessionExpertEntity
import com.helix.core.storage.entity.SessionPermissionConfigEntity
import com.helix.core.storage.entity.SessionPermissionDefaultsEntity
import com.helix.core.storage.entity.SessionPermissionDraftEntity
import com.helix.core.storage.entity.SkillEntity
import com.helix.core.storage.entity.SkillSnapshotEntity
import com.helix.core.storage.entity.ToolAvailabilityEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolCallReviewEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.entity.TurnReviewReceiptEntity
import com.helix.core.storage.entity.TurnRuntimeRecordEntity

/**
 * Helix local database. Before the first public release the current entity graph is the version-1
 * baseline; no migration chain for earlier internal development schemas is supported.
 *
 * Foreign keys and indexes are declared by the current entities and enforced by Room. The
 * committed schema export is the current baseline contract, not an upgrade fixture. Secrets never
 * enter the schema: provider/MCP rows store aliases only and large bodies live in the content store.
 */
@Database(
    entities =
        [
            com.helix.core.storage.entity.ProjectEntity::class,
            com.helix.core.storage.entity.ProjectSessionEntity::class,
            SessionEntity::class,
            com.helix.core.storage.entity.WorkspaceEntity::class,
            com.helix.core.storage.entity.SessionWorkspaceEntity::class,
            com.helix.core.storage.entity.ModelCallWorkspaceEntity::class,
            com.helix.core.storage.entity.ConnectorInstallationEntity::class,
            com.helix.core.storage.entity.ConnectorSkillOwnershipEntity::class,
            com.helix.core.storage.entity.SessionConnectorEntity::class,
            com.helix.core.storage.entity.ConnectorEndpointEntity::class,
            MessageEntity::class,
            MessageAttachmentEntity::class,
            MessageReferenceSnapshotEntity::class,
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
            com.helix.core.storage.entity.GoalControlEntity::class,
            SessionPermissionConfigEntity::class,
            ToolAvailabilityEntity::class,
            SessionPermissionDefaultsEntity::class,
            SessionPermissionDraftEntity::class,
            SessionExpertEntity::class,
            com.helix.core.storage.entity.SessionRunControlEntity::class,
            com.helix.core.storage.entity.SessionInputEntity::class,
            com.helix.core.storage.entity.SessionInputAttachmentEntity::class,
            ToolCallReviewEntity::class,
            TurnRuntimeRecordEntity::class,
            TurnReviewReceiptEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
@Suppress("TooManyFunctions") // Room @Database requires one accessor per persisted aggregate/feature table.
abstract class HelixDatabase : RoomDatabase() {
    abstract fun projectDao(): com.helix.core.storage.dao.ProjectDao

    abstract fun connectorDao(): com.helix.core.storage.dao.ConnectorDao

    abstract fun goalControlDao(): com.helix.core.storage.dao.GoalControlDao

    abstract fun sessionInputDao(): com.helix.core.storage.dao.SessionInputDao

    abstract fun workspaceDao(): com.helix.core.storage.dao.WorkspaceDao

    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun messageAttachmentDao(): MessageAttachmentDao

    abstract fun messageReferenceSnapshotDao(): MessageReferenceSnapshotDao

    abstract fun turnDao(): TurnDao

    abstract fun turnRuntimeRecordDao(): TurnRuntimeRecordDao

    abstract fun turnReviewReceiptDao(): TurnReviewReceiptDao

    abstract fun modelCallDao(): ModelCallDao

    abstract fun toolCallDao(): ToolCallDao

    abstract fun toolResultDao(): ToolResultDao

    abstract fun toolCallReviewDao(): ToolCallReviewDao

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

    abstract fun sessionPermissionConfigDao(): SessionPermissionConfigDao

    abstract fun sessionPermissionDraftDao(): SessionPermissionDraftDao

    abstract fun sessionExpertDao(): SessionExpertDao

    abstract fun toolAvailabilityDao(): ToolAvailabilityDao

    abstract fun sessionPermissionDefaultsDao(): SessionPermissionDefaultsDao

    abstract fun sessionRunControlDao(): com.helix.core.storage.dao.SessionRunControlDao

    companion object {
        const val DATABASE_NAME = "helix.db"
    }
}
