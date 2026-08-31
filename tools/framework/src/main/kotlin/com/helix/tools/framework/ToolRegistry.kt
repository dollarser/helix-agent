package com.helix.tools.framework

import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion

/**
 * The in-process tool registry (HXA-030): the single source of registered
 * tool contracts for the Agent core, Policy and UI.
 *
 * Semantics:
 * - a tool's identity is (name, version). Registering an EXISTING (name,
 *   version) pair FAILS (duplicate registration is an error, never a
 *   silent overwrite — a silent overwrite could invalidate approvals bound
 *   to the previous contract);
 * - the same NAME at a NEW version is a legal evolution (old approvals bound
 *   to the old version/schema hash remain bound to it; the new version needs
 *   its own approvals — HXA-034);
 * - the namespace + MCP class-floor invariants live in [ToolDescriptor]'s
 *   constructor (single enforcement point), so every registered descriptor
 *   already satisfies them;
 * - the registry is thread-safe: registrations are synchronized and every
 *   read returns an immutable snapshot.
 *
 * The registry holds descriptors only. Schema SUBSET validity is enforced in
 * the [ToolDescriptor] constructor (HXA-031), so the registry never sees an
 * out-of-subset schema; policy is NOT evaluated here (HXA-033); execution
 * happens only in the dispatcher (HXA-035).
 */
class ToolRegistry(
    sources: List<ToolSource> = emptyList(),
) {
    private val lock = Any()
    private val byNameVersion: MutableMap<Pair<ToolName, ToolVersion>, ToolDescriptor> = LinkedHashMap()

    init {
        sources.forEach { source ->
            source.load().forEach { descriptor ->
                registerInternal(descriptor)
            }
        }
    }

    /**
     * Registers one descriptor. Fails (IllegalArgumentException) on a
     * duplicate (name, version) or an invariant violation — see the class
     * KDoc. Dynamic (MCP) registrations use the same path as construction.
     */
    fun register(descriptor: ToolDescriptor): ToolDescriptor =
        synchronized(lock) {
            registerInternal(descriptor)
            descriptor
        }

    /** The exact (name, version) contract; fails when unknown. */
    fun resolve(
        name: ToolName,
        version: ToolVersion,
    ): ToolDescriptor {
        val descriptor =
            synchronized(lock) { byNameVersion[name to version] }
        require(descriptor != null) { "unknown tool ${name.value} v$version" }
        return descriptor
    }

    /** The highest registered version of [name], or null when the name is unknown. */
    fun resolveLatest(name: ToolName): ToolDescriptor? =
        synchronized(lock) {
            byNameVersion.values.filter { it.name == name }.maxByOrNull { it.version.value }
        }

    /** Every registered (name, version) contract, sorted by name then version. */
    fun all(): List<ToolDescriptor> =
        synchronized(lock) {
            byNameVersion.values.sortedWith(compareBy({ it.name.value }, { it.version.value }))
        }

    /**
     * The MODE VIEW of the registry: the latest version of every tool whose
     * operation class is in [allowedOperationClasses], sorted by name.
     *
     * This is the list a mode's tool table is built from. Plan mode passes
     * exactly `setOf(ToolOperationClass.READ_ONLY)` — the filter is on the
     * OPERATION CLASS ONLY; a risk-level (L0/L1) check can never substitute
     * it (doc 02 section 7; core:agent ModePolicy enforces the same rule per
     * call).
     */
    fun visibleFor(allowedOperationClasses: Set<ToolOperationClass>): List<ToolDescriptor> {
        val latestByName = LinkedHashMap<ToolName, ToolDescriptor>()
        all().forEach { descriptor ->
            val current = latestByName[descriptor.name]
            if (current == null || descriptor.version.value > current.version.value) {
                latestByName[descriptor.name] = descriptor
            }
        }
        return latestByName.values
            .filter { it.operationClass in allowedOperationClasses }
            .sortedBy { it.name.value }
    }

    private fun registerInternal(descriptor: ToolDescriptor) {
        // The namespace/class-floor invariants are enforced once, in
        // ToolDescriptor's constructor (single enforcement point); the
        // registry owns the uniqueness rule only.
        val key = descriptor.name to descriptor.version
        require(byNameVersion[key] == null) {
            "duplicate tool registration: ${descriptor.name.value} v${descriptor.version.value}"
        }
        byNameVersion[key] = descriptor
    }
}
