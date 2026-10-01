package com.helix.app.chat

import com.helix.core.agent.ToolLoopProgress
import com.helix.core.model.Capability
import com.helix.core.model.ToolOperationClass
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Uses persisted canonical business arguments and complete content fingerprints, never UI summaries alone. */
internal class DurableToolLoopProgress(
    private val storage: HelixStorage,
    private val registry: ToolRegistry,
) {
    fun evaluate(turnId: String): ToolLoopProgress.Decision {
        val reset =
            storage.auditEvents
                .listByCorrelation(turnId)
                .lastOrNull { it.type == RESET }
                ?.redactedPayload
        val calls = storage.toolCalls.recentByTurn(turnId, ToolLoopProgress.WINDOW)
        val start = if (reset == null) 0 else calls.indexOfLast { it.id == reset } + 1
        return fromHistory(calls.drop(start), storage.toolResults::byToolCall) { name, version ->
            registry.all().find { it.name.value == name && it.version.value.toString() == version }
        }
    }

    fun reset(
        turnId: String,
        id: String,
        now: Long,
    ) {
        val last = storage.toolCalls.recentByTurn(turnId, 1).lastOrNull() ?: return
        storage.auditEvents.append(id, turnId, RESET, "agent", last.id, now)
    }

    companion object {
        private const val RESET = "loop.user_steering"
        private val LIVE_OBSERVATIONS = setOf("get_goal", "time.now", "code.linux.job.status", "code.linux.job.collect")

        fun fromHistory(
            calls: List<ToolCallEntity>,
            resultFor: (String) -> ToolResultEntity?,
            descriptorFor: (String, String) -> ToolDescriptor?,
        ): ToolLoopProgress.Decision {
            val observations =
                calls.takeLast(ToolLoopProgress.WINDOW).map { call ->
                    val descriptor = descriptorFor(call.name, call.version)
                    val result = resultFor(call.id)
                    val denied = call.state in setOf("DENIED", "FAILED") && result?.status in setOf("DENIED", "FAILED")
                    val stableRead =
                        descriptor?.operationClass == ToolOperationClass.READ_ONLY &&
                            descriptor.idempotency == Idempotency.IDEMPOTENT &&
                            Capability.ACCESSIBILITY_AUTOMATION !in descriptor.requiredCapabilities &&
                            call.name !in LIVE_OBSERVATIONS && call.state == "COMPLETED" && result?.verified == true
                    if (result == null || (!denied && !stableRead)) {
                        null
                    } else {
                        listOf(
                            call.name,
                            call.version,
                            call.argsHash,
                            result.status,
                            result.contentRef,
                            result.summary,
                        ).let { values -> JsonArray(values.map { JsonPrimitive(it) }).toString() }
                    }
                }
            return ToolLoopProgress.evaluate(observations)
        }
    }
}
