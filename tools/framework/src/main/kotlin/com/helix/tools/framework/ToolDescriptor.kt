package com.helix.tools.framework

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.Hex
import com.helix.core.model.RiskLevel
import com.helix.core.model.Sha256
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The stable, registry-registered contract of one tool (architecture doc
 * section 7, HXA-030).
 *
 * Identity is ([name], [version]): the name is the stable model-visible name
 * (built-in short names or `mcp.<server>.<tool>`), the version is the
 * monotonic tool API version. The [schemaHash] binds the INPUT + OUTPUT
 * schema contract: any schema change is a new hash, and the hash participates
 * in approval binding (doc 10 section 4.3: a schema change invalidates all
 * approvals for the tool).
 *
 * [operationClass] describes the effect of the operation (orthogonal to
 * [baseRisk]): Plan mode filters on [ToolOperationClass.READ_ONLY] ONLY and
 * never substitutes a risk-level check (core:agent ModePolicy consumes this
 * descriptor). [requiredCapabilities] uses core:model's unified
 * [Capability] enum; capability states describe what the app CAN do and never
 * replace per-call Tool Policy. [executionTarget] is the target KIND the tool
 * runs on (the concrete target instance is bound at dispatch, HXA-035).
 *
 * [timeout] and [maxOutputBytes] are the hard LIMITS the dispatcher enforces
 * (doc 02 section 7.1: Timeout/Cancellation 包装 + 输出大小限制); they are
 * part of the contract, not runtime tuning.
 *
 * Both [inputSchema] and [outputSchema] must satisfy the tool schema SUBSET
 * ([ToolSchema], HXA-031) at construction — an unknown keyword, an out-of-type
 * keyword, or a malformed keyword value makes the descriptor unconstructible,
 * which is how "unknown keyword 拒绝注册" is enforced for every source.
 */
@Suppress("LongParameterList") // one parameter per doc 02 section 7 contract field (+ execution target + origin)
data class ToolDescriptor(
    val name: ToolName,
    val version: ToolVersion,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val operationClass: ToolOperationClass,
    val baseRisk: RiskLevel,
    val timeout: Duration,
    val maxOutputBytes: Long,
    val requiredCapabilities: Set<Capability>,
    val idempotency: Idempotency,
    val executionTarget: ExecutionTargetType,
    val origin: ToolOrigin,
) {
    init {
        require(description.isNotBlank()) { "tool description must not be blank" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "tool description exceeds $MAX_DESCRIPTION_LENGTH characters"
        }
        require(timeout > Duration.ZERO) { "tool timeout must be positive" }
        require(timeout <= MAX_TIMEOUT) {
            "tool timeout exceeds the $MAX_TIMEOUT framework cap (long-running work belongs to Goals, not tools)"
        }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(maxOutputBytes <= MAX_OUTPUT_BYTES_CAP) {
            "maxOutputBytes exceeds the ${MAX_OUTPUT_BYTES_CAP} byte framework cap"
        }
        // Schema subset gate (HXA-031, "unknown keyword 拒绝注册"): a
        // descriptor with an out-of-subset schema cannot be constructed, so
        // no source and no registry path can register one (single
        // enforcement point).
        val inputViolations = ToolSchema.check(inputSchema)
        require(inputViolations.isEmpty()) {
            "invalid input schema for ${name.value}: ${inputViolations.joinToString("; ")}"
        }
        val outputViolations = ToolSchema.check(outputSchema)
        require(outputViolations.isEmpty()) {
            "invalid output schema for ${name.value}: ${outputViolations.joinToString("; ")}"
        }
        // Namespace separation (doc 02 section 7.1): MCP names come from MCP
        // origins only, built-in names from built-in origins only.
        val isMcpName = name.value.startsWith(MCP_NAME_PREFIX)
        require((origin is ToolOrigin.McpOrigin) == isMcpName) {
            "origin/name mismatch: ${name.value} has origin ${origin::class.simpleName}"
        }
        if (origin is ToolOrigin.McpOrigin) {
            // MCP tools call OUT to a server: from the device's point of view
            // the effect is at least NETWORK. A server-provided readOnlyHint
            // can never classify (or reclassify) a tool as READ_ONLY
            // (doc 02 section 7 / doc 10 section 4.4).
            require(operationClass != ToolOperationClass.READ_ONLY) {
                "MCP tool ${name.value} can never be classified READ_ONLY (server hints are not classification)"
            }
        }
    }

    /**
     * Stable SHA-256 over the canonical input + output schema (see
     * [ToolSchemaCanonicalizer]). Deterministic for the same parsed schema
     * regardless of key order; different for any semantic difference.
     */
    val schemaHash: Sha256 = schemaHashOf(inputSchema, outputSchema)

    companion object {
        /** The `mcp.` name prefix (doc 02 section 7.1, doc 10 section 4.3). */
        const val MCP_NAME_PREFIX = "mcp."

        const val MAX_DESCRIPTION_LENGTH = 1024

        /**
         * A tool that runs for days is a Goal, not a tool: the dispatcher's
         * timeout is a hard bound on one model-visible call (doc 02 section
         * 7.1), and unbounded tool timeouts would break the turn state machine.
         */
        val MAX_TIMEOUT: Duration = 24.hours

        /** The tool output byte cap must stay below the transport's 8 MiB wire body bound. */
        const val MAX_OUTPUT_BYTES_CAP: Long = 8 * 1024 * 1024

        /** A byte that can never occur in the canonical form: the hash input is unambiguous. */
        private const val SCHEMA_HASH_SEPARATOR = "\u0000"

        /**
         * The stable schema hash: SHA-256 over the canonical input schema, a
         * separator byte that cannot occur in the canonical form, and the
         * canonical output schema.
         */
        fun schemaHashOf(
            inputSchema: JsonObject,
            outputSchema: JsonObject,
        ): Sha256 {
            val canonical =
                ToolSchemaCanonicalizer.canonicalize(inputSchema) +
                    SCHEMA_HASH_SEPARATOR +
                    ToolSchemaCanonicalizer.canonicalize(outputSchema)
            return Sha256(
                Hex.encode(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))),
            )
        }
    }
}
