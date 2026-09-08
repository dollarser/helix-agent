package com.helix.app.privacy

import com.helix.app.APP_SCOPE_ID
import com.helix.app.a2a.A2aAppService
import com.helix.app.chat.ChatService
import com.helix.app.goal.GoalDeletionCoordinator
import com.helix.app.mcp.McpAppService
import com.helix.app.provider.ProviderService
import com.helix.app.root.RootModule
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.feature.browser.BrowserController

data class DeletionResult(
    val subject: String,
    val deletedItems: Int,
)

/** User-action-only irreversible deletion facade; it is never registered as a model Tool. */
class PrivacyDeletionService(
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    private val browser: BrowserController,
    private val providers: ProviderService,
    private val mcp: McpAppService,
    private val a2a: A2aAppService,
    private val skills: SkillRepository,
    private val chat: ChatService,
    private val cancelGoalReminder: (String) -> Unit,
) {
    fun deleteSession(sessionId: String): DeletionResult {
        chat.preparePermanentDeletion(sessionId)
        val manifest = storage.deleteSessionPermanently(sessionId)
        manifest.unreferencedWorkspacePaths.forEach { relativePath ->
            workspace.deletePermanentlyForPrivacy(FileScopePath(APP_SCOPE_ID, relativePath))
        }
        return DeletionResult("session:$sessionId", 1 + manifest.deletedContentBodies)
    }

    suspend fun deleteProvider(providerId: String): DeletionResult {
        providers.delete(providerId)
        return DeletionResult("provider:$providerId", 1)
    }

    fun deleteMcpServer(serverId: String): DeletionResult {
        mcp.delete(serverId)
        return DeletionResult("mcp:$serverId", 1)
    }

    fun deleteA2aAgent(agentId: String): DeletionResult {
        a2a.delete(agentId)
        return DeletionResult("a2a:$agentId", 1)
    }

    fun deleteImportedSkill(key: SkillKey): DeletionResult {
        skills.removePermanentlyForPrivacy(key)
        return DeletionResult("skill:${key.name}", 1)
    }

    fun deleteGoal(goalId: String): DeletionResult {
        GoalDeletionCoordinator(storage, cancelGoalReminder).delete(goalId)
        if (chat.reminderGoal.value == goalId) chat.dismissGoalReminder()
        return DeletionResult("goal:$goalId", 1)
    }

    fun clearWorkspace(): DeletionResult {
        workspace.clearForPrivacy(APP_SCOPE_ID)
        return DeletionResult("workspace:$APP_SCOPE_ID", 1)
    }

    fun clearSiteData(): DeletionResult {
        browser.clearCookies()
        browser.clearCache()
        browser.clearHistory()
        return DeletionResult("browser-site-data", 1)
    }

    fun closeRootSession(): DeletionResult {
        RootModule.closeSession()
        return DeletionResult("root-session", 1)
    }
}
