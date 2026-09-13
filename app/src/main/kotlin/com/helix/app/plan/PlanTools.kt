package com.helix.app.plan

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.storage.repository.PlanLifecycleState
import com.helix.core.storage.repository.PlanRepository
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * The `plan.submit` built-in tool (research doc section 4.3; HX2-05): Plan mode's structured
 * termination tool (the `exit_plan_mode` analogue). Plan mode is read-only research; the plan
 * itself is NOT a paragraph of assistant text — it is a versioned [PlanArtifact], and this is
 * the ONLY way it leaves the model.
 *
 * Classification: READ_ONLY at L0. Its one side effect is internal harness state — the
 * persisted plan row (REVIEW_REQUIRED/READY) and nothing else: no user-visible local
 * mutation, no egress. That is what lets the PLAN mode filter admit it (core:agent
 * [ModePolicy]: Plan allows only READ_ONLY at dynamic risk <= L1); classifying it as a
 * mutation would deny it in the one mode where the doc requires it.
 *
 * The harness flow (doc 4.3) is enforced AROUND this executor, not inside it: the dispatcher
 * validates the arguments against the input schema (stage 1) and verifies the output against
 * the output schema (stage 7); this executor then persists the artifact REVIEW_REQUIRED. The
 * turn keeps running — the model reads the persisted-facts output and, per the description,
 * ends the turn; the plan stays inert until the user's explicit decision (approve / revise /
 * cancel) through [PlanReviewService].
 */
object PlanTools {
    const val NAME: String = "plan.submit"

    const val VERSION: Int = 1

    /** The registered contract; no required capabilities (in-process persist only). */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Submits the completed plan for the user's review; this is the ONLY way to finish Plan mode. " +
                    "Call it once the plan is complete, with the full structured plan (objective, ordered steps, " +
                    "acceptance criteria, and known assumptions/risks). After a successful call the plan is " +
                    "persisted and AWAITING THE USER'S REVIEW: the user will decide to execute it, revise it or " +
                    "cancel it. End the turn immediately after a successful call — make no further tool calls " +
                    "and do not start executing the plan yourself.",
            inputSchema = Schema.input(),
            outputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive(ToolSchema.TYPE_OBJECT))
                    put(
                        "properties",
                        buildJsonObject {
                            put("planId", Schema.string(64))
                            put("version", Schema.integer())
                            put("hash", Schema.string(64))
                            put("state", Schema.string(32))
                        },
                    )
                    put(
                        "required",
                        JsonArray(
                            listOf(
                                JsonPrimitive("planId"),
                                JsonPrimitive("version"),
                                JsonPrimitive("hash"),
                                JsonPrimitive("state"),
                            ),
                        ),
                    )
                    put("additionalProperties", JsonPrimitive(false))
                },
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L0,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            // Each successful call inserts a NEW plan row; the same call twice is not a no-op.
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    /** The implementation; [plans] is the same repository the app container exposes (ADR-0001). */
    fun executor(
        plans: PlanRepository,
        idGenerator: () -> String,
    ): ToolExecutor =
        object : ToolExecutor {
            @Suppress("TooGenericExceptionCaught") // any Room/SQLite failure type; the safe label is the contract
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) {
                    return ToolExecutorResult.Cancelled
                }
                val result =
                    try {
                        val artifact = planFromArgs(call.args, idGenerator)
                        plans.save(artifact, PlanLifecycleState.READY.name, null)
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("planId", JsonPrimitive(artifact.id.value))
                                put("version", JsonPrimitive(artifact.version.toLong()))
                                put("hash", JsonPrimitive(artifact.sha256().hex))
                                put("state", JsonPrimitive(PlanLifecycleState.READY.name))
                            },
                            auditDetail =
                                buildJsonObject {
                                    put("planId", JsonPrimitive(artifact.id.value))
                                    put("version", JsonPrimitive(artifact.version.toLong()))
                                    put("hash", JsonPrimitive(artifact.sha256().hex))
                                },
                        )
                    } catch (e: IllegalArgumentException) {
                        // The artifact was never built (or the atomic persist aborted): no row
                        // was written, so the failure is confirmed side-effect-free. (The
                        // dispatcher already schema-validated the args; this is the
                        // defense-in-depth second check.)
                        ToolExecutorResult.Failed(e.message ?: "the plan is invalid", sideEffectFree = true)
                    } catch (e: Exception) {
                        // The stable safe label is the model-visible contract; only the redacted
                        // failure class rides along for the audit event (HXA-053: no bodies).
                        ToolExecutorResult.Failed(
                            detail = "the plan could not be stored",
                            sideEffectFree = false,
                            auditDetail =
                                buildJsonObject {
                                    put(
                                        "failureClass",
                                        JsonPrimitive(e::class.java.simpleName),
                                    )
                                },
                        )
                    }
                return result
            }
        }

    /** Registers both the contract and the implementation in the given registries. */
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        plans: PlanRepository,
        idGenerator: () -> String,
    ) {
        val descriptor = descriptor()
        registry.register(descriptor)
        implementations.register(descriptor, executor(plans, idGenerator))
    }

    // --- argument projection: schema-validated JSON -> the validated domain type ---

    private fun planFromArgs(
        args: JsonObject,
        idGenerator: () -> String,
    ): PlanArtifact =
        PlanArtifact(
            id = PlanId(idGenerator()),
            objective = requiredString(args, "objective"),
            assumptions = optionalStringList(args, "assumptions"),
            steps =
                (args["steps"] as? JsonArray)
                    ?.map { element ->
                        val step =
                            element as? JsonObject
                                ?: throw IllegalArgumentException("steps items must be objects")
                        PlanStep(
                            title = requiredString(step, "title"),
                            description = requiredString(step, "description"),
                        )
                    }
                    ?: throw IllegalArgumentException("steps is required"),
            acceptanceCriteria = requiredStringList(args, "acceptanceCriteria"),
            risks = optionalStringList(args, "risks"),
            version = 1,
        )

    private fun requiredString(
        args: JsonObject,
        field: String,
    ): String =
        (args[field] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("$field is required")

    private fun requiredStringList(
        args: JsonObject,
        field: String,
    ): List<String> =
        (args[field] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.content ?: throw IllegalArgumentException("$field items must be strings") }
            ?: throw IllegalArgumentException("$field is required")

    private fun optionalStringList(
        args: JsonObject,
        field: String,
    ): List<String> =
        (args[field] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.content ?: throw IllegalArgumentException("$field items must be strings") }
            ?: emptyList()

    /** The input/output schemas of the contract (ToolSchema subset, enforced at construction). */
    private object Schema {
        fun input(): JsonObject =
            buildJsonObject {
                put("type", JsonPrimitive(ToolSchema.TYPE_OBJECT))
                put(
                    "properties",
                    buildJsonObject {
                        put("objective", string(1024, minLength = 1))
                        put("assumptions", stringArray(maxItems = 32))
                        put(
                            "steps",
                            buildJsonObject {
                                put("type", JsonPrimitive(ToolSchema.TYPE_ARRAY))
                                put("minItems", JsonPrimitive(1))
                                put("maxItems", JsonPrimitive(64))
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", JsonPrimitive(ToolSchema.TYPE_OBJECT))
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("title", string(256, minLength = 1))
                                                put("description", string(1024, minLength = 1))
                                            },
                                        )
                                        put(
                                            "required",
                                            JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("description"))),
                                        )
                                        put("additionalProperties", JsonPrimitive(false))
                                    },
                                )
                            },
                        )
                        put("acceptanceCriteria", stringArray(maxItems = 32, minItems = 1))
                        put("risks", stringArray(maxItems = 32))
                    },
                )
                put(
                    "required",
                    JsonArray(
                        listOf(JsonPrimitive("objective"), JsonPrimitive("steps"), JsonPrimitive("acceptanceCriteria")),
                    ),
                )
                put("additionalProperties", JsonPrimitive(false))
            }

        fun string(
            maxLength: Int,
            minLength: Int = 0,
        ): JsonObject =
            buildJsonObject {
                put("type", JsonPrimitive(ToolSchema.TYPE_STRING))
                put("minLength", JsonPrimitive(minLength))
                put("maxLength", JsonPrimitive(maxLength))
            }

        fun stringArray(
            maxItems: Int,
            minItems: Int = 0,
        ): JsonObject =
            buildJsonObject {
                put("type", JsonPrimitive(ToolSchema.TYPE_ARRAY))
                put("minItems", JsonPrimitive(minItems))
                put("maxItems", JsonPrimitive(maxItems))
                put("items", string(1024, minLength = 1))
            }

        fun integer(): JsonObject =
            buildJsonObject {
                put("type", JsonPrimitive(ToolSchema.TYPE_INTEGER))
            }
    }
}
