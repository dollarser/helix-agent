package com.helix.app.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.approval.SessionPermissionService
import com.helix.app.approval.StorageApprovalBroker
import com.helix.app.approval.StorageAuditSink
import com.helix.app.chat.AttachmentStagingSupport
import com.helix.app.chat.ChatService
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.profile.PersistedSafetyProfileStore
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.BuiltInModelCatalog
import com.helix.app.provider.BuiltInModelRegistration
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.model.SystemClock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledAgentStore
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceOpener
import com.helix.provider.api.wire.WireClient
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolDispatcher
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** All conversation/history/workspace/tool state is isolated. The sole optional network is builtin Qwen. */
internal class ScheduledRuntimeFixture(
    wire: WireClient? = null,
) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "scheduled-runtime-${UUID.randomUUID()}.db"
    private val ledgerName = "scheduled-ledger-$name"
    private val root = File(context.cacheDir, name).apply { mkdirs() }
    val storage = HelixStorage.open(context, name, File(root, "content"))
    val ledger = ScheduledAgentStore(context, ledgerName)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val clock = SystemClock()
    val engine =
        createRoomTurnEngine(storage, clock, ::newId)
    var afterTool: () -> Unit = {}
    val pipeline = pipeline()
    val providers: ProviderService
    val chat: ChatService
    val execution: RuntimeScheduledAgentExecution
    val service: ScheduledAgentService

    init {
        BuiltInModelRegistration.ensureRegistered(storage)
        // Only a supplied mock wire gets a synthetic account binding. The optional real-wire
        // path retains the signed-out default and must satisfy real account authorization.
        val status =
            if (wire == null) {
                ProviderTestStatusStore(InMemoryLineStore())
            } else {
                ProviderTestStatusStore(InMemoryLineStore(), accountIdentity = { "synthetic-scheduled-account" })
            }
        status.builtInCatalog.save(BuiltInModelCatalog(System.currentTimeMillis(), listOf(BuiltInModel.MODEL_ID)))
        if (wire != null) {
            check(status.builtInCatalog.accountAvailable()) { "Mock scheduled fixture requires its synthetic identity" }
            check(BuiltInModel.MODEL_ID in status.builtInCatalog.read().models) {
                "Mock catalog must be bound to the same synthetic identity"
            }
        }
        val factory =
            if (wire == null) {
                ProviderFactory({ error("No user credentials") }, ProviderFactory.defaultWire(), { error("No images") })
            } else {
                ProviderFactory({ error("No user credentials") }, wire, { error("No images") }, builtInWire = wire)
            }
        providers = ProviderService(storage, factory, status, idGenerator = ::newId, scope = scope)
        listOf("bound", "foreground").forEach {
            storage.sessions.create(
                it,
                "Synthetic $it",
                BuiltInModel.PROVIDER_ID,
                BuiltInModel.MODEL_ID,
                System.currentTimeMillis(),
            )
        }
        val staging =
            AttachmentStagingSupport(
                AttachmentImporter(
                    SafImportPipeline(ScopeRootResolver { root.toPath() }, SafSourceOpener { error("No attachments") }),
                ),
                "fixture",
                { error("No attachments") },
                { root.toPath().resolve(it.relativePath) },
            )
        chat =
            ChatService(
                storage,
                providers,
                PersistedSafetyProfileStore(InMemoryLineStore(), false),
                toolPipeline = pipeline,
                idGenerator = ::newId,
                turnEngine = engine,
                scope = scope,
                attachmentStaging = staging,
            )
        execution = RuntimeScheduledAgentExecution(storage, providers, chat, engine, pipeline, ledger)
        service = ScheduledAgentService(ledger, execution, ScheduledFakeWake())
        providers.refresh()
        com.helix.app.test
            .discoverFixtureTool(pipeline, "bound", "time.now")
    }

    suspend fun ready() {
        withTimeout(15_000) {
            providers.rows.first { rows ->
                rows.any {
                    it.id == BuiltInModel.PROVIDER_ID &&
                        it.modelSelectable(BuiltInModel.MODEL_ID)
                }
            }
        }
        chat.openSession("foreground")
        withTimeout(10_000) { chat.screen.first { it.openSessionId == "foreground" } }
    }

    suspend fun execute(
        prompt: String,
        maxRuntimeMillis: Long = ScheduledRuntimeBudget.DEFAULT_MILLIS,
    ): com.helix.core.storage.scheduled.ScheduledAttempt {
        ready()
        val due = LocalDateTime.now(ZoneOffset.UTC).plusNanos(250_000_000)
        val draft =
            ScheduledAgentRule(
                newId(),
                "Synthetic runtime",
                prompt,
                "",
                "",
                "bound",
                due.toString(),
                "UTC",
                "ONCE",
                enabled = true,
            )
        service.save(service.prepare(draft, maxRuntimeMillis))
        val rule = requireNotNull(ledger.dao.rule(draft.id))
        delay(maxOf(0, requireNotNull(rule.nextAt) - System.currentTimeMillis()) + 10)
        withTimeout(110_000) { service.fire(rule.id, rule.revision, requireNotNull(rule.nextAt)) }
        return ledger.dao
            .observeAttempts()
            .first()
            .single()
    }

    private fun pipeline(): ToolPipeline {
        val delegate = TimeNowTool.executor(clock)
        val registry =
            ToolRegistry().apply {
                register(
                    TimeNowTool.descriptor(),
                    object : com.helix.tools.framework.ToolExecutor {
                        override fun execute(call: com.helix.tools.framework.ExecutableToolCall) =
                            delegate.execute(call).also { afterTool() }
                    },
                )
            }
        val broker =
            StorageApprovalBroker(storage.approvals, clock, ::newId, cardSink = {
                _,
                _,
                ->
                error("Synthetic safe tools need no approval")
            })
        val audit = StorageAuditSink(storage.auditEvents, ::newId)
        val workspace: (String) -> String? = { storage.sessions.find(it)?.directoryRef ?: "app" }
        val permissions =
            SessionPermissionService(storage.sessionPermissionConfigs, storage.toolAvailability, workspace)
        val dispatcher =
            ToolDispatcher(
                clock,
                registry,
                (context.applicationContext as HelixApplication).appContainer.capabilityCenter,
                com.helix.core.policy
                    .PolicyEngine(clock),
                broker,
                audit,
                sessionPermissions = permissions,
                toolAvailability = permissions,
                effectClassifier =
                    com.helix.app.tool
                        .SessionToolEffectClassifier(workspace),
            )
        return ToolPipeline(
            registry,
            dispatcher,
            broker,
            audit,
            ToolScheduler(clock, dispatcher, registry),
            disabledToolFilter = { session, descriptor ->
                val states =
                    permissions.statesFor(
                        descriptor.origin.canonicalOf(),
                        descriptor.name.value,
                        session,
                        null,
                    )
                com.helix.core.policy
                    .effectiveAvailability(states.global, states.workspace, states.session) !=
                    com.helix.core.model.ToolAvailabilityState.DISABLED
            },
        )
    }

    override fun close() {
        service.close()
        runBlocking { job.cancelAndJoin() }
        ledger.close()
        storage.close()
        context.deleteDatabase(name)
        context.deleteDatabase(ledgerName)
        root.deleteRecursively()
    }

    private fun newId() = UUID.randomUUID().toString()
}
