package com.helix.tools.framework

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.core.policy.EgressRequest
import com.helix.core.policy.UserScope
import kotlinx.serialization.json.JsonObject

/**
 * The platform-owned concurrency profile of one validated ToolCall (doc 11 section 3.1,
 * roadmap HXA-037). Generated AFTER policy and BEFORE execution, exclusively from the
 * registered descriptor, the normalized arguments and the trusted request facts.
 *
 * The model, an MCP annotation and a Skill cannot override ANY field: the only input a
 * tool can influence is its registered operation class and the platform's resource-key
 * extraction ([ResourceKeyExtractor] — platform code, reviewed, per-tool). An MCP
 * `isConcurrencySafe`-style self-claim has no path into this type.
 *
 * [runtimeKeys] name actual non-reentrant engines. User-file/origin/effect metadata
 * is informational, not a promise of business-result correctness or a global lock.
 */
data class EffectFootprint(
    val operationClass: ToolOperationClass,
    val executionTargetId: ExecutionTargetType,
    val scopeIds: Set<String>,
    val resourceKeys: Set<String>,
    val originKeys: Set<String>,
    val runtimeKeys: Set<String> = emptySet(),
) {
    /**
     * Only shared engine state conflicts here. Result ordering and permission checks
     * remain independent of whether tasks read or write the same user resource.
     */
    fun conflictsWith(other: EffectFootprint): Boolean = runtimeKeys.intersect(other.runtimeKeys).isNotEmpty()
}

/**
 * Platform extraction of stable resource keys from a call's NORMALIZED arguments
 * (doc 11 section 3.1: Workspace canonical path, SAF document ID, browser tab/generation,
 * Accessibility package/window, calendar/account, Runtime job lane...).
 *
 * Implementations are platform code. These keys describe resources; their intersection
 * is not an execution prohibition. Do not parse shell text to infer safe business outcomes.
 */
fun interface ResourceKeyExtractor {
    fun resourceKeys(
        toolName: String,
        args: JsonObject,
    ): Set<String>
}

/** Extracts no resource keys: the first-version default (tools supply keys as they land). */
object NoResourceKeys : ResourceKeyExtractor {
    override fun resourceKeys(
        toolName: String,
        args: JsonObject,
    ): Set<String> = emptySet()
}

/**
 * No blanket writes/code/Root/Accessibility exclusion. The native QuickJS singleton
 * alone shares an engine/death protocol. Isolated JS and PRoot jobs have independent
 * execution identities; backend resource limits apply without blocking unrelated work.
 */
object EffectFootprintBuilder {
    fun build(
        descriptor: ToolDescriptor?,
        args: JsonObject,
        executionTarget: ExecutionTargetType,
        scope: UserScope?,
        egress: EgressRequest?,
        extractor: ResourceKeyExtractor,
    ): EffectFootprint {
        val operationClass = descriptor?.operationClass ?: ToolOperationClass.LOCAL_MUTATION
        val resourceKeys = mutableSetOf<String>()
        descriptor?.let { resourceKeys += extractor.resourceKeys(it.name.value, args) }
        val originKeys = egress?.endpoint?.origin?.let { setOf(it) } ?: emptySet()
        val runtimeKeys =
            if (executionTarget == ExecutionTargetType.LOCAL_QUICKJS &&
                (args["access"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "native"
            ) {
                setOf("engine:quickjs-native")
            } else {
                emptySet()
            }
        return EffectFootprint(
            operationClass = operationClass,
            executionTargetId = executionTarget,
            scopeIds = scope?.toScopeRef()?.let { setOf(it) } ?: emptySet(),
            resourceKeys = resourceKeys.toSortedSet(),
            originKeys = originKeys.toSortedSet(),
            runtimeKeys = runtimeKeys,
        )
    }
}
