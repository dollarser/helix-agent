package com.helix.app

import android.content.Context
import com.helix.app.approval.StorageApprovalBroker
import com.helix.app.approval.StorageAuditSink
import com.helix.app.audit.AuditLogService
import com.helix.app.capability.StorageCapabilityGrantRecorder
import com.helix.app.capability.SystemCapabilityResolver
import com.helix.app.chat.ChatService
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
import com.helix.provider.api.CredentialLookup
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolDispatcher
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolScheduler

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
}

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

    init {
        // The first real tool (HXA-035): `time.now` — the canonical L0 no-approval path.
        TimeNowTool.register(toolRegistry, toolImplementations, appClock)
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

    override val chatService: ChatService =
        ChatService(
            storage = storage,
            providerService = providerService,
            profileStore = profileStore,
            clock = appClock,
            idGenerator = { idGenerator.next() },
            toolPipeline = toolPipeline,
        ).also {
            // The broker (built above) publishes pending cards into the chat timeline.
            approvalCardSink.sink = it::onApprovalCard
        }

    private companion object {
        const val PREFS_NAME = "helix-ui"
    }
}
