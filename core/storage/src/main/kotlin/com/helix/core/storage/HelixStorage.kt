// [Migration].addMigrations(vararg) is Room's only migration-registration API: spreading the
// fixed migration array is the idiom, not a hot path.
@file:Suppress("SpreadOperator")

package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.A2aAgentRepository
import com.helix.core.storage.repository.A2aCapabilityRepository
import com.helix.core.storage.repository.A2aTaskRepository
import com.helix.core.storage.repository.ApprovalRepository
import com.helix.core.storage.repository.ArtifactRepository
import com.helix.core.storage.repository.AuditEventRepository
import com.helix.core.storage.repository.CapabilityGrantRepository
import com.helix.core.storage.repository.ExecutionRepository
import com.helix.core.storage.repository.ExecutionTargetRepository
import com.helix.core.storage.repository.GoalRepository
import com.helix.core.storage.repository.GoalRunRepository
import com.helix.core.storage.repository.HighSensitivityRuleRepository
import com.helix.core.storage.repository.InteractionReceiptRepository
import com.helix.core.storage.repository.McpCapabilityRepository
import com.helix.core.storage.repository.McpServerRepository
import com.helix.core.storage.repository.MessageAttachmentRepository
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
class HelixStorage internal constructor(
    internal val database: HelixDatabase,
    val contentStore: ContentStore,
    val secrets: SecretStore,
) {
    val sessions: SessionRepository by lazy { SessionRepository(database.sessionDao()) }
    val messages: MessageRepository by lazy { MessageRepository(database.messageDao(), contentStore) }
    val messageAttachments: MessageAttachmentRepository by lazy {
        MessageAttachmentRepository(database.messageAttachmentDao())
    }
    val turns: TurnRepository by lazy { TurnRepository(database.turnDao()) }
    val modelCalls: ModelCallRepository by lazy { ModelCallRepository(database.modelCallDao()) }
    val toolCalls: ToolCallRepository by lazy { ToolCallRepository(database.toolCallDao()) }
    val toolResults: ToolResultRepository by lazy { ToolResultRepository(database.toolResultDao(), contentStore) }
    val approvals: ApprovalRepository by lazy { ApprovalRepository(database.approvalDao()) }
    val interactionReceipts: InteractionReceiptRepository by lazy {
        InteractionReceiptRepository(database.interactionReceiptDao())
    }
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

    val highSensitivityRules: HighSensitivityRuleRepository by lazy {
        HighSensitivityRuleRepository(database.highSensitivityRuleDao())
    }

    val a2aAgents: A2aAgentRepository by lazy { A2aAgentRepository(database.a2aAgentDao()) }
    val a2aCapabilities: A2aCapabilityRepository by lazy {
        A2aCapabilityRepository(database.a2aAgentDao(), database.a2aCapabilityDao())
    }
    val a2aTasks: A2aTaskRepository by lazy { A2aTaskRepository(database.a2aTaskDao()) }

    fun withTransaction(block: () -> Unit) {
        database.runInTransaction(Runnable { block() })
    }

    /** Closes the database; a later [open] over the same file sees exactly the committed rows. */
    fun close() {
        database.close()
    }

    /**
     * Irreversible, user-confirmed privacy erase. Normal conversation removal remains archive.
     * Room rows and unbound interaction/audit rows commit atomically; content-addressed bodies
     * are removed afterwards only when no surviving row references the same hash. Workspace paths
     * are returned to the app layer, which owns the scoped filesystem and deletes only paths no
     * surviving artifact row references.
     */
    fun deleteSessionPermanently(sessionId: String): SessionDeletionManifest {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val messageRefs = database.messageDao().contentRefsBySession(sessionId)
        val resultRefs = database.toolResultDao().contentRefsBySession(sessionId)
        val artifactPaths = database.artifactDao().listBySession(sessionId).map { it.relativePath }
        database.runInTransaction {
            require(database.sessionDao().byId(sessionId) != null) { "session not found: $sessionId" }
            database.interactionReceiptDao().deleteBySession(sessionId)
            database.auditEventDao().deleteForSession(sessionId)
            check(database.sessionDao().deletePermanently(sessionId) == 1) { "session deletion lost its target" }
        }
        val deletedBodies =
            (messageRefs + resultRefs).distinct().mapNotNull { encoded ->
                val stillReferenced =
                    database.messageDao().countByContentRef(encoded) > 0 ||
                        database.toolResultDao().countByContentRef(encoded) > 0
                if (!stillReferenced && contentStore.delete(ContentRef.parse(encoded))) encoded else null
            }
        val unreferencedPaths = artifactPaths.distinct().filter { database.artifactDao().countByRelativePath(it) == 0 }
        return SessionDeletionManifest(sessionId, deletedBodies.size, unreferencedPaths)
    }

    companion object {
        /**
         * The complete committed migration chain (v1→v2 approval binding, v2→v3 receipts,
         * v3→v4 message attachments, v4→v5 high-sensitivity egress rules, v5→v6 A2A snapshots,
         * v6→v7 A2A task correlation).
         * Both production
         * entries register it: Room does NOT auto-discover migrations, so a missing registration
         * is a startup crash on any device holding an older schema (`A migration from N to M is
         * required`) — fresh installs never exercise it, which is exactly why this must be
         * explicit and device-tested.
         */
        private val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                HelixDatabase.MIGRATION_1_2,
                HelixDatabase.MIGRATION_2_3,
                HelixDatabase.MIGRATION_3_4,
                HelixDatabase.MIGRATION_4_5,
                HelixDatabase.MIGRATION_5_6,
                HelixDatabase.MIGRATION_6_7,
            )

        fun create(context: Context): HelixStorage {
            val database =
                Room
                    .databaseBuilder(context, HelixDatabase::class.java, HelixDatabase.DATABASE_NAME)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            val contentStore = FileContentStore(File(context.filesDir, CONTENT_DIR))
            return HelixStorage(database, contentStore, AndroidKeystoreSecretStore.create(context))
        }

        /**
         * Opens a database with an explicit name and content directory. Callers see [HelixStorage]
         * only — the Room types stay inside this module (feature layers never touch them).
         */
        fun open(
            context: Context,
            databaseName: String,
            contentDir: File,
        ): HelixStorage {
            val database =
                Room
                    .databaseBuilder(context, HelixDatabase::class.java, databaseName)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            return HelixStorage(database, FileContentStore(contentDir), AndroidKeystoreSecretStore.create(context))
        }

        private const val CONTENT_DIR = "helix-content"
    }
}

data class SessionDeletionManifest(
    val sessionId: String,
    val deletedContentBodies: Int,
    val unreferencedWorkspacePaths: List<String>,
)
