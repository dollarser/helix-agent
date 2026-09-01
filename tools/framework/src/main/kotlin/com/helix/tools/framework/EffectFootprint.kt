package com.helix.tools.framework

import com.helix.core.model.Capability
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
 * [exclusive] is true when the call must not run concurrently with ANY other call
 * (doc 11 section 3.1: 未知效应、写入/删除、代码执行、Root、Accessibility、同 tab /
 * 同 Runtime lane 默认排他; 多个写操作首版保守串行).
 */
data class EffectFootprint(
    val operationClass: ToolOperationClass,
    val executionTargetId: ExecutionTargetType,
    val scopeIds: Set<String>,
    val resourceKeys: Set<String>,
    val originKeys: Set<String>,
    val exclusive: Boolean,
) {
    /**
     * Two calls may run concurrently ONLY when BOTH are proven read-only, neither is
     * exclusive, they are on different exclusive lanes and they share no resource or
     * origin key (doc 11 section 3.1: 仅当两个调用都被证明为只读、footprint 不冲突、
     * 执行域允许并发且共享输出预算仍有余量时才能并行).
     */
    fun conflictsWith(other: EffectFootprint): Boolean =
        exclusive ||
            other.exclusive ||
            resourceKeys.intersect(other.resourceKeys).isNotEmpty() ||
            originKeys.intersect(other.originKeys).isNotEmpty()
}

/**
 * Platform extraction of stable resource keys from a call's NORMALIZED arguments
 * (doc 11 section 3.1: Workspace canonical path, SAF document ID, browser tab/generation,
 * Accessibility package/window, calendar/account, Runtime job lane...).
 *
 * Implementations are platform code (one per tool family, registered by the app); they
 * may read the arguments but never declare safety — a missing key only makes the call
 * MORE conservative through the caller's conflict rule. The default extracts nothing:
 * with no shared key, read-only calls on different lanes never conflict, and any
 * non-read-only call is exclusive anyway.
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
 * Builds the footprint from trusted facts (doc 11 section 3.1). Rules:
 * - non-[ToolOperationClass.READ_ONLY] (write/delete/code/privileged — the unknown-effect
 *   case is conservative by construction) → [EffectFootprint.exclusive];
 * - Root / Accessibility ACTIONS (descriptor requires `ROOT_SHELL` /
 *   `ACCESSIBILITY_AUTOMATION`) → [EffectFootprint.exclusive], even when read-only;
 * - QuickJS / PRoot / CLI targets are single-concurrent LANES: the lane key enters
 *   [EffectFootprint.resourceKeys], so two calls on the same lane conflict (serialize)
 *   while different lanes may run concurrently;
 * - an egress origin enters [EffectFootprint.originKeys] so parallel calls to the same
 *   network origin serialize;
 * - [ResourceKeyExtractor] adds the tool's platform resource keys.
 */
object EffectFootprintBuilder {
    private val LANE_KEYS =
        mapOf(
            ExecutionTargetType.LOCAL_QUICKJS to "lane:quickjs",
            ExecutionTargetType.LOCAL_PROOT to "lane:proot",
            ExecutionTargetType.LOCAL_CLI_RUNTIME to "lane:cli",
        )
    private val EXCLUSIVE_CAPABILITIES =
        setOf(Capability.ROOT_SHELL, Capability.ACCESSIBILITY_AUTOMATION)

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
        LANE_KEYS[executionTarget]?.let { resourceKeys += it }
        descriptor?.let { resourceKeys += extractor.resourceKeys(it.name.value, args) }
        val originKeys = egress?.endpoint?.origin?.let { setOf(it) } ?: emptySet()
        val exclusive =
            operationClass != ToolOperationClass.READ_ONLY ||
                (descriptor?.requiredCapabilities?.intersect(EXCLUSIVE_CAPABILITIES)?.isNotEmpty() == true)
        return EffectFootprint(
            operationClass = operationClass,
            executionTargetId = executionTarget,
            scopeIds = scope?.toScopeRef()?.let { setOf(it) } ?: emptySet(),
            resourceKeys = resourceKeys.toSortedSet(),
            originKeys = originKeys.toSortedSet(),
            exclusive = exclusive,
        )
    }
}
