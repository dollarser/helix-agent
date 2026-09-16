package com.helix.app

import com.helix.app.a2a.A2aAppService
import com.helix.app.audit.AuditLogService
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService
import com.helix.app.mcp.McpAppService
import com.helix.app.privacy.PrivacyDeletionService
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.AgentRuntime
import com.helix.core.policy.CapabilityCenter
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillRepository
import com.helix.feature.browser.BrowserController
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeScopeService

/**
 * The app's own private workspace scope id (HXA-042); the only file scope wired yet. Internal
 * (not a companion constant) so the HXA-068 Advanced egress-rule UI — which must bind new rules
 * to the SAME scope the app's tools address — can reference it without duplicating the literal.
 */
internal const val APP_SCOPE_ID = "app"

/**
 * The app's manual DI container (M0 pattern; no framework). HXA-028 adds the
 * production provider/chat stack: one shared [HelixStorage] (recovery +
 * providers + sessions), the safety-profile store, and the two services the
 * UI talks to. HXA-032 adds the [CapabilityCenter]: live system-state
 * resolver plus the write-only `capability_grants` audit recorder. The UI
 * never sees DAOs, OkHttp or the Keystore directly (AGENTS.md; doc 02
 * section 16).
 */
interface AppContainer {
    val shellRepository: ShellRepository

    val storage: HelixStorage

    val profileStore: SafetyProfileStore
    val lanScopeStore: com.helix.app.network.LanScopeStore

    val runControlStore: RunControlStore

    val firstLaunch: FirstLaunchStore

    val providerService: ProviderService

    val chatService: ChatService

    /**
     * The unified agent entry point (research doc section 34; HX2-01). Every producer (Chat /
     * Goal / Share / Voice / Widget / Channel) drives an agent turn ONLY through this — never the
     * model provider or the tool pipeline directly.
     */
    val agentRuntime: AgentRuntime

    val capabilityCenter: CapabilityCenter

    /**
     * The tool pipeline (roadmap HXA-036): registered tool contracts + implementations,
     * the dispatcher (doc 11 single entry point) and the storage-backed approval broker.
     * The UI reaches it ONLY through the chat service — never the dispatcher or broker
     * directly (AGENTS: UI never touches the execution layer).
     */
    val toolPipeline: ToolPipeline

    val connectorService: com.helix.app.connector.ConnectorService
        get() = error("Connector service is unavailable in this container")

    val mcpService: McpAppService

    val a2aService: A2aAppService

    val connectorInstallationService: com.helix.app.connector.ConnectorInstallationService?
        get() = null

    val skillInstallationService: com.helix.app.skills.SkillInstallationService?
        get() = null

    val skillAuthoringService: com.helix.app.skills.SkillAuthoringService?
        get() = null

    val skillImportService: SkillImportService

    val skillRepository: SkillRepository

    /** The audit log page's service (bounded, redacted records only). */
    val auditLogService: AuditLogService

    /** Explicit user-action privacy deletion; never exposed to the model Tool Registry. */
    val privacyDeletionService: PrivacyDeletionService

    /**
     * The SAF adapter bundle (HXA-044): persisted tree grants, the ContentResolver adapters and
     * the fail-closed import/export pipelines. The UI drives it; the model never sees a
     * `content://` URI from it (doc 10).
     */
    val featureFiles: FeatureFiles

    /**
     * The file-manager facade (HXA-046): the user-facing browse / sort / preview / mutate / trash /
     * share seam over the same [WorkspaceArtifactStore] the `files.*` tools use. The user drives it
     * directly (their own files — not a model ToolCall, so no per-call approval gate); the model
     * never sees it, and it resolves paths through the same containment-enforced scope boundary.
     */
    val fileManager: FileManagerService

    /**
     * The browser facade (HXA-060): the hardened WebView tab state, the URL-policy choke
     * point and the download queue. The UI binds to it; nothing else in the app touches
     * the WebView (AGENTS: WebView is owned by the browser feature).
     */
    val browser: BrowserController

    /**
     * The SAF tree scope service (HXA-057): persisted tree grants (the SAME [SafGrantStore] the
     * import/export bundle uses) + real-time re-verification (grant / provider identity / root
     * document / read-write mode). The file manager browses these scopes read-only; the model and
     * tools see only the model-opaque `scopeId` + relative path (doc 10: 模型只看到 scopeId).
     */
    val safTree: SafTreeScopeService
}
