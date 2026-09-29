package com.helix.tools.framework

import com.helix.core.model.ToolBindingRef
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion

/** One atomic descriptor/executor fact source for exposure, scheduling and execution admission. */
@Suppress("TooManyFunctions") // One publication authority exposes catalog, reference lookup and admission.
class ToolRegistry(
    /** Stable for one host installation, changes when packaged native code is replaced. */
    private val hostImplementationRevision: String = "test-host",
) {
    private val store = ToolBindingStore(hostImplementationRevision)

    fun register(
        descriptor: ToolDescriptor,
        executor: ToolExecutor,
        implementationRevision: String = descriptor.contractHash.hex,
    ): ToolDescriptor =
        registerBatch(listOf(ToolBinding(descriptor, executor, implementationRevision))).single().descriptor

    fun registerBatch(bindings: List<ToolBinding>): List<RegisteredToolBinding> = store.register(bindings)

    /** Validate first; optional local storage commit precedes publication without holding admission's lock.
     * The callback must commit atomically or throw, cannot publish recursively, and cannot execute tools/network.
     */
    fun replaceOwner(
        owner: String,
        bindings: List<ToolBinding>,
        beforePublish: () -> Unit = {},
    ): List<RegisteredToolBinding> = store.replace(owner, bindings, beforePublish)

    fun replaceMcpServer(
        serverId: String,
        bindings: List<ToolBinding>,
        beforePublish: () -> Unit = {},
    ): List<ToolDescriptor> = replaceOwner(bindingOwner("mcp", serverId), bindings, beforePublish).map { it.descriptor }

    fun replaceA2aAgent(
        agentId: String,
        bindings: List<ToolBinding>,
        beforePublish: () -> Unit = {},
    ): List<ToolDescriptor> = replaceOwner(bindingOwner("a2a", agentId), bindings, beforePublish).map { it.descriptor }

    fun snapshot(): List<RegisteredToolBinding> = store.snapshot()

    fun resolveBinding(
        name: ToolName,
        version: ToolVersion,
    ): RegisteredToolBinding =
        requireNotNull(store.resolve(name, version)) { "unknown tool ${name.value} v${version.value}" }

    fun resolveBinding(ref: ToolBindingRef): RegisteredToolBinding? = store.resolve(ref)

    fun <T : Any> admit(
        ref: ToolBindingRef,
        commit: () -> T,
    ): T? = store.admit(ref, commit)

    fun resolve(
        name: ToolName,
        version: ToolVersion,
    ): ToolDescriptor = resolveBinding(name, version).descriptor

    fun executor(
        name: ToolName,
        version: ToolVersion,
    ): ToolExecutor = resolveBinding(name, version).executor

    fun contains(
        name: ToolName,
        version: ToolVersion,
    ): Boolean = store.resolve(name, version) != null

    fun resolveLatest(name: ToolName): ToolDescriptor? =
        all().filter { it.name == name }.maxByOrNull { it.version.value }

    fun all(): List<ToolDescriptor> =
        snapshot().map { it.descriptor }.sortedWith(compareBy({ it.name.value }, { it.version.value }))

    fun visibleFor(allowedOperationClasses: Set<ToolOperationClass>): List<ToolDescriptor> =
        all()
            .groupBy { it.name }
            .values
            .map { versions -> versions.maxBy { it.version.value } }
            .filter { it.operationClass in allowedOperationClasses }
            .sortedBy { it.name.value }
}
