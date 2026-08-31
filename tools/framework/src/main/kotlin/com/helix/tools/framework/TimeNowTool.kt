package com.helix.tools.framework

import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * The `time.now` built-in tool (doc 01 P0 core tool list, doc 09 P0 list): returns local
 * time, UTC and the time zone. This is the FIRST tool the dispatcher (HXA-035) runs in
 * production code: L0 risk, READ_ONLY operation class, idempotent, in-process — the
 * canonical "no approval needed" path of the pipeline.
 *
 * The input schema is `{"type":"object","additionalProperties":false}`: the tool takes no
 * arguments, so ONLY the empty object is valid (a string or a stray property is rejected
 * at the validate stage, before capability/policy/execution).
 *
 * The clock is injected so tests observe deterministic outputs (security doc section 6.1:
 * no real clock in unit tests).
 */
object TimeNowTool {
    const val NAME: String = "time.now"

    const val VERSION: Int = 1

    /** The registered contract; no required capabilities (pure in-process read). */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = "Returns the current local time, UTC time and time zone ID.",
            inputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", JsonPrimitive(false))
                },
            outputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put("localIso", stringSchema())
                            put("utcIso", stringSchema())
                            put("timeZoneId", stringSchema())
                        },
                    )
                    put(
                        "required",
                        JsonArray(
                            listOf(JsonPrimitive("localIso"), JsonPrimitive("utcIso"), JsonPrimitive("timeZoneId")),
                        ),
                    )
                    put("additionalProperties", JsonPrimitive(false))
                },
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L0,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    /** The implementation bound to [descriptor]; the executor checks the cancel signal. */
    fun executor(clock: Clock): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) {
                    return ToolExecutorResult.Cancelled
                }
                val now = clock.now()
                val output =
                    buildJsonObject {
                        put("localIso", JsonPrimitive(now.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()))
                        put("utcIso", JsonPrimitive(now.toString()))
                        put("timeZoneId", JsonPrimitive(ZoneId.systemDefault().id))
                    }
                return ToolExecutorResult.Completed(output)
            }
        }

    /** Registers both the contract and the implementation in the given registries. */
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        clock: Clock,
    ) {
        val descriptor = descriptor()
        registry.register(descriptor)
        implementations.register(descriptor, executor(clock))
    }

    private fun stringSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("string")) }
}
