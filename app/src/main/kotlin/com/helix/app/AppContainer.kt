package com.helix.app

import android.content.Context
import com.helix.app.allfiles.AllFilesModule
import com.helix.app.approval.StorageApprovalBroker
import com.helix.app.approval.StorageAuditSink
import com.helix.app.audit.AuditLogService
import com.helix.app.capability.StorageCapabilityGrantRecorder
import com.helix.app.capability.SystemCapabilityResolver
import com.helix.app.chat.AttachmentStagingSupport
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService
import com.helix.app.internal.PrefsLineStore
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.PersistedSafetyProfileStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.CleartextBindingStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.app.tool.ApprovalCardSinkHolder
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.IdGenerator
import com.helix.core.model.RandomIdGenerator
import com.helix.core.model.SystemClock
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.PolicyEngine
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.resolveFileScopePath
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.ContentResolverSafDestinationOpener
import com.helix.feature.files.ContentResolverSafDestinationVerifier
import com.helix.feature.files.ContentResolverSafGrantProbe
import com.helix.feature.files.ContentResolverSafMetadataReader
import com.helix.feature.files.ContentResolverSafSourceOpener
import com.helix.feature.files.ContentResolverSafTreeCheck
import com.helix.feature.files.ContentResolverSafTreeReader
import com.helix.feature.files.SafExportPipeline
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafTreeScopeAccess
import com.helix.feature.files.SafTreeScopeService
import com.helix.provider.api.CredentialLookup
import com.helix.runtime.quickjs.JsExecutionClient
import com.helix.runtime.quickjs.tool.CodeJavascriptRunTool
import com.helix.tools.files.EditTool
import com.helix.tools.files.FilesArchiveTool
import com.helix.tools.files.FilesCopyTool
import com.helix.tools.files.FilesDeleteTool
import com.helix.tools.files.FilesExtractTool
import com.helix.tools.files.FilesListTool
import com.helix.tools.files.FilesMkdirTool
import com.helix.tools.files.FilesMoveTool
import com.helix.tools.files.FilesSearchTool
import com.helix.tools.files.FilesStatTool
import com.helix.tools.files.ReadTool
import com.helix.tools.files.WriteTool
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolDispatcher
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolScheduler
import java.nio.file.Path

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

    val firstLaunch: FirstLaunchStore

    val providerService: ProviderService

    val chatService: ChatService

    val capabilityCenter: CapabilityCenter

    /**
     * The tool pipeline (roadmap HXA-036): registered tool contracts + implementations,
     * the dispatcher (doc 11 single entry point) and the storage-backed approval broker.
     * The UI reaches it ONLY through the chat service — never the dispatcher or broker
     * directly (AGENTS: UI never touches the execution layer).
     */
    val toolPipeline: ToolPipeline

    /** The audit log page's service (bounded, redacted records only). */
    val auditLogService: AuditLogService

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
     * The SAF tree scope service (HXA-057): persisted tree grants (the SAME [SafGrantStore] the
     * import/export bundle uses) + real-time re-verification (grant / provider identity / root
     * document / read-write mode). The file manager browses these scopes read-only; the model and
     * tools see only the model-opaque `scopeId` + relative path (doc 10: 模型只看到 scopeId).
     */
    val safTree: SafTreeScopeService
}

/**
 * SAF file import/export bundle (HXA-044, platform adapter layer). [SafGrantStore] holds the
 * persisted tree grants whose `content://` URIs never reach the model (doc 10: 模型只看到
 * scopeId); the ContentResolver adapters implement the pipeline seams; the pipelines are
 * fail-closed against a lying provider (doc 07).
 */
class FeatureFiles(
    val grantStore: SafGrantStore,
    val metadataReader: ContentResolverSafMetadataReader,
    val sourceOpener: ContentResolverSafSourceOpener,
    val importPipeline: SafImportPipeline,
    val exportPipeline: SafExportPipeline,
    val destinationOpener: ContentResolverSafDestinationOpener,
    val destinationVerifier: ContentResolverSafDestinationVerifier,
    val grantProbe: ContentResolverSafGrantProbe,
)

internal class DefaultAppContainer(
    context: Context,
) : AppContainer {
    override val shellRepository: ShellRepository = FakeShellRepository()

    override val storage: HelixStorage = HelixStorage.create(context)

    private val lineStore = PrefsLineStore(context, PREFS_NAME)

    override val profileStore: SafetyProfileStore =
        PersistedSafetyProfileStore(lineStore, AdvancedProfileAvailability.ADVANCED_AVAILABLE)

    override val firstLaunch: FirstLaunchStore = FirstLaunchStore(lineStore)

    private val idGenerator: IdGenerator = RandomIdGenerator()

    /**
     * Request-time credential resolution (HXA-025 seam): the Keystore secret is
     * read when the wire request is built — never at UI construction, never
     * into UI state (NFR-007). Keyless providers use the fixed non-secret
     * placeholder (their servers ignore the auth header).
     */
    private val credentials: CredentialLookup =
        CredentialLookup { alias ->
            if (alias.value == ProviderFactory.NO_KEY_ALIAS) {
                ProviderFactory.NO_KEY_PLACEHOLDER
            } else {
                storage.secrets.get(alias)
            }
        }

    override val providerService: ProviderService =
        ProviderService(
            storage = storage,
            factory = ProviderFactory(credentials, ProviderFactory.defaultWire()),
            bindings = CleartextBindingStore(lineStore),
            testStatus = ProviderTestStatusStore(lineStore),
            idGenerator = { idGenerator.next() },
        ).also { it.refresh() }

    /**
     * Capability Center (HXA-032, doc 9 section 2): [SystemCapabilityResolver] queries the real
     * system state on every check; [StorageCapabilityGrantRecorder] writes the result to
     * `capability_grants` for audit only — the stored rows never replace the execution-time
     * check (doc 02 section 9.1).
     */
    override val capabilityCenter: CapabilityCenter =
        CapabilityCenter(
            SystemCapabilityResolver(context),
            StorageCapabilityGrantRecorder(storage),
        )

    // --- HXA-036: the tool pipeline (dispatcher + storage-backed approval broker + audit sink) ---

    /** One process clock shared by the chat service, the policy engine, the broker and the sink. */
    private val appClock: SystemClock = SystemClock()

    private val toolRegistry: ToolRegistry = ToolRegistry()

    private val toolImplementations: ToolImplementationRegistry = ToolImplementationRegistry()

    /**
     * The app's own workspace scope (HXA-042): a fixed scope id whose root is the app-private
     * `workspaces/app` directory. The SAF import pipeline (HXA-044) also targets this scope's
     * `input/` region. All-files scopes (`af-<root>`, HXA-045) resolve through [AllFilesModule].
     * The root is created lazily on first use and never handed to the model (doc 10).
     */
    private val appScopeRoot: Path =
        java.io.File(context.filesDir, "workspaces/app").toPath().also {
            java.nio.file.Files
                .createDirectories(it)
        }

    private val scopeRoots: ScopeRootResolver =
        ScopeRootResolver { scopeId ->
            if (scopeId == APP_SCOPE_ID) {
                appScopeRoot
            } else {
                // HXA-045: an all-files scope (af-<root>) resolves ONLY in the developer flavor,
                // and only while MANAGE_EXTERNAL_STORAGE is granted and the root enabled
                // (AllFilesModule.resolveScopeRoot is the fail-closed seam; the consumer no-op
                // always returns null). The resolved path never reaches the model (doc 10);
                // containment stays enforced by resolveFileScopePath downstream.
                AllFilesModule.resolveScopeRoot(scopeId)
                    ?: throw ScopeNotAvailable("unknown scope: $scopeId")
            }
        }

    private val workspaceStore: WorkspaceArtifactStore =
        WorkspaceArtifactStore(scopeRoots).also { it.ensureLayout(APP_SCOPE_ID) }

    /**
     * The main-process QuickJS execution client (HXA-053): a stateless Binder façade that binds
     * the non-exported one-shot [com.helix.runtime.quickjs.JsExecutionService] per execution.
     * Constructed once for the process; the Service manifest entry ships with :runtime:quickjs
     * and merges into both variants. The QuickJS tool executes through it on the dispatcher's
     * (never main) executor thread.
     */
    private val jsExecutionClient: JsExecutionClient = JsExecutionClient(context)

    /**
     * The persisted SAF tree grant registry (HXA-044/HXA-057): one shared [SafGrantStore] under the
     * app-private `workspaces/` directory, used by BOTH the SAF import/export bundle and the SAF
     * tree scope service. The `content://` URIs it holds never reach the model (doc 10: 模型只看到
     * scopeId).
     */
    private val safGrantStore: SafGrantStore =
        SafGrantStore(java.io.File(context.filesDir, "workspaces/saf-grants.json").toPath())

    /**
     * The SAF tree scope service (HXA-057: persisted SAF tree scope 接线). Re-verifies every grant
     * in real time (grant / provider identity / root document / read-write mode) and fails closed on
     * revocation, provider-gone, restart, read-only grant or URI change. The file manager consumes
     * it read-only; the model and tools see only the model-opaque `scopeId` + relative path.
     */
    override val safTree: SafTreeScopeService =
        SafTreeScopeService(safGrantStore, ContentResolverSafTreeCheck(context.contentResolver))

    /**
     * The SAF tree scope access the file manager consumes (HXA-057): the scope service + the
     * read-only `DocumentsContract` browse backend + an app-private share-staging directory. SAF
     * scopes are browse/preview/share-only in this milestone (the all-files precedent: mutations
     * hidden; a read-only grant's write re-verification fails closed regardless).
     */
    private val safAccess: SafTreeScopeAccess =
        SafTreeScopeAccess(
            service = safTree,
            reader = ContentResolverSafTreeReader(context.contentResolver, safGrantStore),
            shareDir = java.io.File(context.filesDir, "workspaces/saf-share").toPath(),
        )

    /**
     * The file-manager facade (HXA-046 + HXA-057): shares the tool pipeline's [workspaceStore] and
     * the same [scopeRoots] scope boundary, so the user's file manager and the model's `files.*`
     * tools address the identical, containment-enforced store. [APP_SCOPE_ID] is the always-present,
     * mutable workspace; all-files roots (developer) are appended read-only, and SAF tree scopes
     * (HXA-057) are appended read-only and re-verified on every browse.
     */
    override val fileManager: FileManagerService =
        FileManagerService(workspaceStore, scopeRoots, APP_SCOPE_ID, safAccess)

    /**
     * SAF adapter bundle (HXA-044; PRD: SAF scope 默认复制到应用私有目录处理). Reuses the shared
     * [safGrantStore] (HXA-057); the import pipeline targets the app workspace `input/` region
     * through the same [scopeRoots] the file tools use, and the export pipeline reads from that
     * scope's user regions.
     */
    override val featureFiles: FeatureFiles =
        run {
            val resolver = context.contentResolver
            val sourceOpener = ContentResolverSafSourceOpener(resolver)
            val destinationOpener = ContentResolverSafDestinationOpener(resolver)
            val destinationVerifier = ContentResolverSafDestinationVerifier(resolver)
            FeatureFiles(
                grantStore = safGrantStore,
                metadataReader = ContentResolverSafMetadataReader(resolver),
                sourceOpener = sourceOpener,
                importPipeline = SafImportPipeline(scopeRoots, sourceOpener),
                exportPipeline = SafExportPipeline(scopeRoots, destinationOpener, destinationVerifier),
                destinationOpener = destinationOpener,
                destinationVerifier = destinationVerifier,
                grantProbe = ContentResolverSafGrantProbe(resolver),
            )
        }

    init {
        // HXA-045: initialize the all-files module (developer flavor builds the roots registry;
        // consumer is a no-op). Runs before any tool can resolve an af- scope.
        AllFilesModule.init(context)
        // The first real tool (HXA-035): `time.now` — the canonical L0 no-approval path.
        TimeNowTool.register(toolRegistry, toolImplementations, appClock)
        // HXA-042: the first non-time.now business tools enter the production tool table. The
        // contractHash gate (ContractHashGateTest / ADR-0011) is the mechanical proof that a
        // security-descriptor change invalidates any approval minted for the old contract.
        ReadTool.register(toolRegistry, toolImplementations, workspaceStore)
        WriteTool.register(toolRegistry, toolImplementations, workspaceStore)
        EditTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesListTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesSearchTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesStatTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesMkdirTool.register(toolRegistry, toolImplementations, workspaceStore)
        // HXA-043: explicit conflict policy (copy/move refuse an existing destination without
        // overwrite) and delete-into-trash; restore and purge stay store seams, not model
        // tools.
        FilesCopyTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesMoveTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesDeleteTool.register(toolRegistry, toolImplementations, workspaceStore)
        // HXA-047: restricted zip/tar create + extract. The format codec and the Zip Slip /
        // expansion / entry-type defenses are shared; the tools only admit scope + region and
        // route containment/quota through the store. Archive writes into work/ only.
        FilesArchiveTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesExtractTool.register(toolRegistry, toolImplementations, workspaceStore)
        // HXA-053: the isolated QuickJS tool. Registered for BOTH consumer and developer
        // (ADR-0013: Standard is the complete product; QuickJS is APK-embedded, no native
        // download). L2 CODE_EXECUTION on the platform's single-concurrency QuickJS lane.
        CodeJavascriptRunTool.register(toolRegistry, toolImplementations) { params, cancel ->
            jsExecutionClient.execute(params, cancel)
        }
    }

    /**
     * The broker publishes approval cards to the chat service. The holder breaks the
     * construction cycle (the broker is built before the chat service that renders its
     * cards); the chat service installs the sink at the end of container construction.
     */
    private val approvalCardSink: ApprovalCardSinkHolder = ApprovalCardSinkHolder()

    /**
     * The production approval broker (roadmap HXA-036): pending records with the full
     * binding hash + 24h window, the UI-decided [decide], and the HXA-034 mint/consume
     * guards as the ONLY path to a typed proof (ADR-0005: no auto-approve path exists).
     */
    override val toolPipeline: ToolPipeline =
        run {
            val broker =
                StorageApprovalBroker(
                    approvals = storage.approvals,
                    clock = appClock,
                    idGenerator = { idGenerator.next() },
                    cardSink = { approvalId, request ->
                        approvalCardSink.deliver(approvalId, request)
                    },
                )
            val auditSink = StorageAuditSink(storage.auditEvents) { idGenerator.next() }
            val dispatcher =
                ToolDispatcher(
                    clock = appClock,
                    registry = toolRegistry,
                    implementations = toolImplementations,
                    capabilityCenter = capabilityCenter,
                    policyEngine = PolicyEngine(appClock),
                    approvals = broker,
                    audit = auditSink,
                )
            // The deterministic scheduler (roadmap HXA-037; doc 11 section 3): default total
            // concurrency 2, hard cap 4 before real-device evidence. The resource gate is
            // the constant default in this first version — low memory / background / thermal
            // signals lower the allowance through this SAME seam in a later HXA (they can
            // only lower it, never raise it, and never touch approvals or result order).
            val scheduler =
                ToolScheduler(
                    clock = appClock,
                    dispatcher = dispatcher,
                    registry = toolRegistry,
                )
            ToolPipeline(toolRegistry, toolImplementations, dispatcher, broker, auditSink, scheduler)
        }

    override val auditLogService: AuditLogService = AuditLogService(storage)

    /**
     * The chat-attachment staging seams (HXA-049, ADR-0014): the EXISTING one-time private SAF
     * import over the shared [featureFiles] pipeline, the source-metadata reader, the app
     * workspace scope the attachments pin into, and the containment-enforced scope-path resolver.
     * [AttachmentStagingSupport.resolveWorkspacePath] returns a REAL path consumed ONLY inside
     * the chat service for hashing / re-materialization — it never reaches UI, logs or the model.
     */
    private val attachmentStaging: AttachmentStagingSupport =
        AttachmentStagingSupport(
            importer = AttachmentImporter(featureFiles.importPipeline),
            workspaceScopeId = APP_SCOPE_ID,
            sourceMetadata = featureFiles.metadataReader::metadata,
            resolveWorkspacePath = { scopePath -> resolveFileScopePath(scopePath, scopeRoots) },
        )

    override val chatService: ChatService =
        ChatService(
            storage = storage,
            providerService = providerService,
            profileStore = profileStore,
            clock = appClock,
            idGenerator = { idGenerator.next() },
            toolPipeline = toolPipeline,
            attachmentStaging = attachmentStaging,
        ).also {
            // The broker (built above) publishes pending cards into the chat timeline.
            approvalCardSink.sink = it::onApprovalCard
        }

    private companion object {
        const val PREFS_NAME = "helix-ui"

        /** The app's own private workspace scope id (HXA-042); the only file scope wired yet. */
        const val APP_SCOPE_ID = "app"
    }
}
