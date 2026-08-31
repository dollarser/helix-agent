package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.ApprovalRepository
import com.helix.core.storage.repository.ArtifactRepository
import com.helix.core.storage.repository.AuditEventRepository
import com.helix.core.storage.repository.CapabilityGrantRepository
import com.helix.core.storage.repository.ExecutionRepository
import com.helix.core.storage.repository.ExecutionTargetRepository
import com.helix.core.storage.repository.GoalRepository
import com.helix.core.storage.repository.GoalRunRepository
import com.helix.core.storage.repository.McpCapabilityRepository
import com.helix.core.storage.repository.McpServerRepository
import com.helix.core.storage.repository.MessageRepository
import com.helix.core.storage.repository.ModelCallRepository
import com.helix.core.storage.repository.PlanRepository
import com.helix.core.storage.repository.ProviderConfigRepository
import com.helix.core.storage.repository.RuntimeInstallRepository
import com.helix.core.storage.repository.SessionRepository
import com.helix.core.storage.repository.SkillRepository
import com.helix.core.storage.repository.SkillSnapshotRepository
import com.helix.core.storage.repository.ToolCallRepository
import com.helix.core.storage.repository.ToolResultRepository
import com.helix.core.storage.repository.TurnRepository
import java.io.File

/**
 * Composition root for local persistence (HXA-014). The UI never touches the DAOs directly
 * (AGENTS.md); feature code receives a [HelixStorage] and works through its repositories.
 *
 * [withTransaction] runs [block] inside a single Room transaction — use it whenever a domain
 * operation must pair writes, e.g. a turn/tool-call state update with its audit event
 * (doc 9.2).
 */
class HelixStorage(
    val database: HelixDatabase,
    val contentStore: ContentStore,
) {
    val sessions: SessionRepository by lazy { SessionRepository(database.sessionDao()) }
    val messages: MessageRepository by lazy { MessageRepository(database.messageDao(), contentStore) }
    val turns: TurnRepository by lazy { TurnRepository(database.turnDao()) }
    val modelCalls: ModelCallRepository by lazy { ModelCallRepository(database.modelCallDao()) }
    val toolCalls: ToolCallRepository by lazy { ToolCallRepository(database.toolCallDao()) }
    val toolResults: ToolResultRepository by lazy { ToolResultRepository(database.toolResultDao(), contentStore) }
    val approvals: ApprovalRepository by lazy { ApprovalRepository(database.approvalDao()) }
    val executions: ExecutionRepository by lazy { ExecutionRepository(database.executionDao()) }
    val artifacts: ArtifactRepository by lazy { ArtifactRepository(database.artifactDao()) }
    val auditEvents: AuditEventRepository by lazy { AuditEventRepository(database.auditEventDao()) }
    val providerConfigs: ProviderConfigRepository by lazy {
        ProviderConfigRepository(database.providerConfigDao())
    }

    val runtimeInstalls: RuntimeInstallRepository by lazy {
        RuntimeInstallRepository(database.runtimeInstallDao())
    }

    val executionTargets: ExecutionTargetRepository by lazy {
        ExecutionTargetRepository(database.executionTargetDao())
    }

    val capabilityGrants: CapabilityGrantRepository by lazy {
        CapabilityGrantRepository(database.capabilityGrantDao())
    }

    val plans: PlanRepository by lazy { PlanRepository(database.planDao()) }
    val goals: GoalRepository by lazy { GoalRepository(database.goalDao()) }
    val goalRuns: GoalRunRepository by lazy { GoalRunRepository(database.goalRunDao()) }
    val mcpServers: McpServerRepository by lazy { McpServerRepository(database.mcpServerDao()) }
    val mcpCapabilities: McpCapabilityRepository by lazy {
        McpCapabilityRepository(database.mcpCapabilityDao())
    }

    val skills: SkillRepository by lazy { SkillRepository(database.skillDao()) }
    val skillSnapshots: SkillSnapshotRepository by lazy { SkillSnapshotRepository(database.skillSnapshotDao()) }

    fun withTransaction(block: () -> Unit) {
        database.runInTransaction(Runnable { block() })
    }

    companion object {
        fun create(context: Context): HelixStorage {
            val database =
                Room
                    .databaseBuilder(context, HelixDatabase::class.java, HelixDatabase.DATABASE_NAME)
                    .build()
            val contentStore = FileContentStore(File(context.filesDir, CONTENT_DIR))
            return HelixStorage(database, contentStore)
        }

        private const val CONTENT_DIR = "helix-content"
    }
}
