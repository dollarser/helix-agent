package com.helix.app.tool

import com.helix.app.approval.StorageApprovalBroker
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.AuditSink
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatcher
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolScheduler

/**
 * The app's tool pipeline bundle (roadmap HXA-036/037): the registered tool contracts +
 * implementations, the production [ToolDispatcher] (doc 11: Dispatcher 唯一入口), the
 * deterministic [ToolScheduler] (bounded platform-decided parallelism, call-order
 * back-fill) and the storage-backed [StorageApprovalBroker]. AppContainer constructs it
 * once per process (doc 02 section 12: AppContainer creates the process-level Tool
 * Registry and the dispatcher stack); the chat service and the UI observe it through this
 * façade — the UI never touches the dispatcher, the scheduler, the broker or storage
 * directly.
 *
 * The HXA-036 registered set is the first real tool, `time.now` (L0, no approval); the
 * mutating tools arrive with their own HXAs and register here.
 */
class ToolPipeline(
    val registry: ToolRegistry,
    val implementations: ToolImplementationRegistry,
    val dispatcher: ToolDispatcher,
    val broker: StorageApprovalBroker,
    val auditSink: AuditSink,
    val scheduler: ToolScheduler,
) {
    /**
     * Resolves the newest registered version of [name] — null when the tool (or the name
     * itself, which must be a valid [ToolName]) is not registered. Model-requested tool
     * calls are matched against the registry this way; an unknown name is a stable
     * dispatcher rejection, not an app crash.
     */
    fun resolveLatest(name: String): ToolDescriptor? =
        runCatching { ToolName(name) }
            .getOrNull()
            ?.let { registry.resolveLatest(it) }

    /** The version the registry binds to [name] (0 when unregistered — a placeholder the
     * dispatcher rejects as unknown before the version is ever consulted). */
    fun versionFor(name: String): ToolVersion = resolveLatest(name)?.version ?: ToolVersion(0)

    /** Clears the dispatcher's same-turn denial set for [turnId] (called at turn end). */
    fun endTurn(turnId: String) {
        dispatcher.endTurn(turnId)
    }
}
