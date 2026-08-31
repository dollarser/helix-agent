package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireInt
import com.helix.core.model.internal.requireLong
import com.helix.core.model.internal.requireObject
import com.helix.core.model.internal.requireObjectField
import com.helix.core.model.internal.requireOptionalString
import com.helix.core.model.internal.requireString
import com.helix.core.model.internal.requireStringArray
import java.time.Duration

/**
 * Per-execution resource limits carried by a [ToolExecutionEnvelope].
 *
 * Storage encoding (`executions.limitsJson`): fixed-order canonical JSON with exactly the fields
 * `timeoutMillis`, `maxOutputBytes` (see ADR-0001).
 */
data class ExecutionLimits(
    val timeout: Duration,
    val maxOutputBytes: Long,
) {
    init {
        require(!timeout.isNegative && timeout > Duration.ZERO) { "timeout must be positive" }
        // The storage encoding is `timeoutMillis` (ADR-0001): sub-millisecond timeouts would
        // truncate to 0 on encode and be rejected again on decode, so the domain value is
        // millisecond-granular by construction. Check the nano part (always [0, 1e9), never
        // overflows) instead of `toNanos()`, which overflows into an ArithmeticException for
        // millisecond values near Long.MAX_VALUE and would escape the IAE decode contract.
        require(timeout.nano % 1_000_000L == 0L) { "timeout must be a whole number of milliseconds" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be > 0" }
    }

    fun toStorageString(): String {
        val pairs =
            listOf(
                "timeoutMillis" to Json.long(timeout.toMillis()),
                "maxOutputBytes" to Json.long(maxOutputBytes),
            )
        return Json.objectBody(pairs)
    }

    companion object {
        internal val FIELDS = listOf("timeoutMillis", "maxOutputBytes")

        fun parse(text: String): ExecutionLimits {
            val fields = parseJson(text).requireObject("ExecutionLimits", FIELDS)
            return ExecutionLimits(
                timeout = Duration.ofMillis(fields.requireLong("timeoutMillis")),
                maxOutputBytes = fields.requireLong("maxOutputBytes"),
            )
        }
    }
}

/**
 * Bounded snapshot handed to a [com.helix.core.model]-level ToolExecutor for one validated
 * tool call (architecture doc sections 4 and 15, modes doc section 8).
 *
 * The envelope is the unit of cross-boundary exchange: it carries protocol version, tool
 * identity, the ToolDescriptor hash and canonical input hash (so the executor can reject any
 * mismatch with the approved call), bounded limits, an optional one-shot approval reference,
 * the audit correlation ID and the artifact manifest of inputs/outputs. The executor must
 * verify hashes against the persisted `tool_calls` row before acting, and transports
 * (in-process, isolated process, PRoot/CLI IPC) attach the bounded byte payload themselves.
 *
 * Remote workers are future work: a new transport may implement the same executor interface,
 * but this baseline contains only local targets and no network code (architecture doc section 15).
 *
 * Storage encoding: fixed-order canonical JSON, `artifactManifest` in call order (see ADR-0001).
 */
data class ToolExecutionEnvelope(
    val protocolVersion: Int,
    val executionId: ExecutionId,
    val targetId: ExecutionTargetId,
    val toolName: ToolName,
    val toolVersion: ToolVersion,
    val descriptorHash: Sha256,
    val inputRef: ArtifactRef,
    val inputHash: Sha256,
    val limits: ExecutionLimits,
    val approvalProofRef: ApprovalId?,
    val correlationId: CorrelationId,
    val artifactManifest: List<ArtifactRef> = emptyList(),
) {
    init {
        require(protocolVersion >= MIN_PROTOCOL_VERSION) {
            "protocolVersion must be >= $MIN_PROTOCOL_VERSION"
        }
        require(artifactManifest.toSet().size == artifactManifest.size) {
            "artifactManifest must not contain duplicate refs"
        }
    }

    fun toStorageString(): String {
        val manifest = Json.array(artifactManifest.map { Json.string(it.value) })
        val pairs =
            listOf(
                "protocolVersion" to Json.long(protocolVersion.toLong()),
                "executionId" to Json.string(executionId.value),
                "targetId" to Json.string(targetId.value),
                "toolName" to Json.string(toolName.value),
                "toolVersion" to Json.long(toolVersion.value.toLong()),
                "descriptorHash" to Json.string(descriptorHash.hex),
                "inputRef" to Json.string(inputRef.value),
                "inputHash" to Json.string(inputHash.hex),
                "limits" to limits.toStorageString(),
                "approvalProofRef" to (approvalProofRef?.let { Json.string(it.value) } ?: Json.NULL_VALUE),
                "correlationId" to Json.string(correlationId.value),
                "artifactManifest" to manifest,
            )
        return Json.objectBody(pairs)
    }

    companion object {
        const val MIN_PROTOCOL_VERSION = 1

        private val FIELDS =
            listOf(
                "protocolVersion",
                "executionId",
                "targetId",
                "toolName",
                "toolVersion",
                "descriptorHash",
                "inputRef",
                "inputHash",
                "limits",
                "approvalProofRef",
                "correlationId",
                "artifactManifest",
            )

        fun parse(text: String): ToolExecutionEnvelope {
            val fields = parseJson(text).requireObject("ToolExecutionEnvelope", FIELDS)
            val limitsFields =
                fields.requireObjectField("limits", "limits", ExecutionLimits.FIELDS)
            val limits =
                ExecutionLimits(
                    timeout = Duration.ofMillis(limitsFields.requireLong("timeoutMillis")),
                    maxOutputBytes = limitsFields.requireLong("maxOutputBytes"),
                )
            return ToolExecutionEnvelope(
                protocolVersion = fields.requireInt("protocolVersion"),
                executionId = ExecutionId(fields.requireString("executionId")),
                targetId = ExecutionTargetId(fields.requireString("targetId")),
                toolName = ToolName(fields.requireString("toolName")),
                toolVersion = ToolVersion(fields.requireInt("toolVersion")),
                descriptorHash = Sha256(fields.requireString("descriptorHash")),
                inputRef = ArtifactRef(fields.requireString("inputRef")),
                inputHash = Sha256(fields.requireString("inputHash")),
                limits = limits,
                approvalProofRef = fields.requireOptionalString("approvalProofRef")?.let { ApprovalId(it) },
                correlationId = CorrelationId(fields.requireString("correlationId")),
                artifactManifest = fields.requireStringArray("artifactManifest").map { ArtifactRef(it) },
            )
        }
    }
}
