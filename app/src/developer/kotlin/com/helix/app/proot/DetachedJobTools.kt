package com.helix.app.proot

import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/** Contracts are defined here; register only with the complete launch/accounting/collection host. */
internal object DetachedJobTools {
    const val START = "code.linux.job.start"
    const val STATUS = "code.linux.job.status"
    const val CANCEL = "code.linux.job.cancel"
    const val COLLECT = "code.linux.job.collect"
    private val resultSchema = buildJsonObject { put("type", "object") }

    fun start(): ToolDescriptor {
        val base = LinuxRunTool.descriptor()
        val properties =
            base.inputSchema
                .getValue("properties")
                .jsonObject
                .toMutableMap()
        properties.remove("timeoutSeconds")
        properties["leaseSeconds"] =
            buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 1_800)
                put(
                    "description",
                    "Non-renewable lease: default 300s, maximum 1800s; other budgets may shorten it.",
                )
            }
        return base.copy(
            name = ToolName(START),
            version = ToolVersion(1),
            description =
                "Start one trusted Linux Job in the developer Runtime, sharing Helix UID and network access. " +
                    "Returns acceptance, not completion. Query/cancel by originalCallId; " +
                    "never replay an uncertain submission.",
            inputSchema = JsonObject(base.inputSchema + ("properties" to JsonObject(properties))),
            outputSchema = resultSchema,
            maxOutputBytes = 4_096,
        )
    }

    fun control(stop: Boolean): ToolDescriptor =
        LinuxRunTool.descriptor().copy(
            name = ToolName(if (stop) CANCEL else STATUS),
            version = ToolVersion(1),
            description =
                if (stop) {
                    "Cancel this session's original Job; its terminal result still needs settlement."
                } else {
                    "Read this session's original Job state without replaying, renewing or importing results."
                },
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "originalCallId",
                                buildJsonObject {
                                    put("type", "string")
                                    put("minLength", 1)
                                    put("maxLength", 128)
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("originalCallId")) })
                    put("additionalProperties", false)
                },
            outputSchema = resultSchema,
            operationClass = if (stop) ToolOperationClass.LOCAL_MUTATION else ToolOperationClass.READ_ONLY,
            baseRisk = if (stop) RiskLevel.L1 else RiskLevel.L0,
            timeout = 30.seconds,
            maxOutputBytes = 4_096,
            idempotency = Idempotency.IDEMPOTENT,
        )

    fun collect(): ToolDescriptor =
        control(true).copy(
            name = ToolName(COLLECT),
            description =
                "Collect this session's original terminal Job, import only its originally approved output target, " +
                    "and settle it. Retry collection after an import failure; never restart the command.",
            timeout = 120.seconds,
        )

    fun parsed(call: ExecutableToolCall): LinuxRunTool.ParsedResult {
        val args = call.args.toMutableMap()
        // Schema validation rejects timeoutSeconds; it cannot override the approved lease.
        args.remove("timeoutSeconds")
        args.remove("leaseSeconds")?.let { args["timeoutSeconds"] = it }
        return when (
            val parsed =
                LinuxRunTool.parsed(
                    call.copy(args = JsonObject(args)),
                    300,
                    1_800,
                    clampToCallDeadline = false,
                )
        ) {
            is LinuxRunTool.ParsedResult.Ok -> {
                parsed
            }

            is LinuxRunTool.ParsedResult.ParseFailure -> {
                parsed.copy(
                    detail = parsed.detail.replace("timeoutSeconds", "leaseSeconds"),
                )
            }
        }
    }
}
