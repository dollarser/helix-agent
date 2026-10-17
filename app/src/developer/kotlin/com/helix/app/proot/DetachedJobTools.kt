package com.helix.app.proot

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
    const val AWAIT = "jobs.await"
    private val resultSchema = buildJsonObject { put("type", "object") }

    fun start(): ToolDescriptor {
        val base = LinuxRunTool.descriptor()
        val properties =
            base.inputSchema
                .getValue("properties")
                .jsonObject
                .toMutableMap()
        properties.remove("timeoutSeconds")
        properties["waitMode"] =
            buildJsonObject {
                put("type", "string")
                put(
                    "enum",
                    buildJsonArray {
                        add(JsonPrimitive("BACKGROUND"))
                        add(JsonPrimitive("AUTO"))
                    },
                )
                put(
                    "description",
                    "BACKGROUND returns acceptance (default). AUTO waits briefly for this same approved Job, " +
                        "then returns its result or original handle; never extends its lease.",
                )
            }
        properties["waitMillis"] =
            buildJsonObject {
                put("type", "integer")
                put("minimum", 250)
                put("maximum", 15_000)
                put(
                    "description",
                    "AUTO wait window only, default 10000ms; independent of the original execution lease.",
                )
            }
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
            version = ToolVersion(2),
            description =
                "Start one trusted Linux Job in the developer Runtime, sharing Helix UID and network access. " +
                    "BACKGROUND returns acceptance; AUTO returns the original result when completed during waiting, " +
                    "otherwise its same handle. Query/cancel by originalCallId or use jobs.await; " +
                    "never replay an uncertain submission.",
            inputSchema = JsonObject(base.inputSchema + ("properties" to JsonObject(properties))),
            outputSchema = resultSchema,
            maxOutputBytes = 4_096,
            discoveryCompanions = setOf(STATUS, CANCEL, COLLECT, AWAIT).map(::ToolName).toSet(),
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
            timeout = 30.seconds,
            maxOutputBytes = 4_096,
            idempotency = Idempotency.IDEMPOTENT,
            discoveryCompanions = setOf(STATUS, CANCEL, COLLECT, AWAIT).map(::ToolName).toSet(),
        )

    fun await(): ToolDescriptor =
        control(false).copy(
            name = ToolName(AWAIT),
            discoveryCompanions = emptySet(),
            description =
                "Wait up to 15 seconds for ANY or ALL original job handles in this session. " +
                    "Waiting does not restart, cancel, renew or collect jobs. Check reason and settlementPending.",
            inputSchema =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        """{
              "type":"object","properties":{
                "handles":{"type":"array","minItems":1,"maxItems":8,
                  "items":{"type":"string","minLength":1,"maxLength":128}},
                "condition":{"type":"string","enum":["ANY","ALL"]}},
              "required":["handles"],"additionalProperties":false
            }""",
                    ).jsonObject,
            maxOutputBytes = 16_384,
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
        args.remove("waitMode")
        args.remove("waitMillis")
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
