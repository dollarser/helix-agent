package com.helix.tools.framework

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * The registration origin kind of a tool source (doc 02 section 7.1).
 */
enum class ToolSourceKind {
    /** Product-code tools with fixed, stable short names. */
    BUILT_IN,

    /** Tools exposed by a connected, user-enabled MCP server. */
    MCP,
}

/**
 * A source of tool registrations (HXA-030).
 *
 * Sources are the ONLY way descriptors enter the [ToolRegistry]: the model
 * can never register a tool, and an MCP tool can only be registered by the
 * source of a connected, user-enabled server (doc 02 section 7.1, doc 10
 * section 4.3). Implementations must be deterministic for the same inputs.
 */
interface ToolSource {
    val kind: ToolSourceKind

    /** The descriptors this source contributes (validated; empty is allowed). */
    fun load(): List<ToolDescriptor>
}

/**
 * The built-in tool source: a fixed set of product-code descriptors.
 *
 * Invariants enforced at construction (fail-closed): the set must contain no
 * duplicate (name, version) pair. (The `mcp.*` namespace rule is enforced
 * once, in [ToolDescriptor]'s constructor, so a built-in-origin descriptor
 * can never carry an MCP name.)
 *
 * The built-in name set is code-defined and can never be extended at runtime
 * from model output (doc 02 section 7.1: 内置工具名不能由模型动态注册).
 */
class BuiltInToolSource(
    private val descriptors: List<ToolDescriptor>,
) : ToolSource {
    override val kind: ToolSourceKind = ToolSourceKind.BUILT_IN

    init {
        requireNoDuplicates(descriptors)
    }

    override fun load(): List<ToolDescriptor> = descriptors
}

/**
 * One MCP tool as seen by Helix: the server's name + schema (UNTRUSTED —
 * server text and hints, doc 10 section 4.4) plus Helix's own classification
 * (TRUSTED — required, explicit, and the only thing that decides
 * [operationClass]).
 *
 * The [operationClass] parameter is Helix's declaration of the tool's effect.
 * The server's annotations travel with the descriptor as
 * [ToolOrigin.McpOrigin.serverProvidedHints] and are NEVER consumed for
 * classification — structurally, no code path lets a server hint lower (or
 * otherwise change) the class. In particular, an MCP tool can never be
 * classified [ToolOperationClass.READ_ONLY] (it calls out to a server; the
 * effect is at least NETWORK).
 */
@Suppress("LongParameterList") // server facts + Helix classification facts, one parameter each
data class McpToolSpec(
    val serverToolName: String,
    val version: ToolVersion,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val operationClass: ToolOperationClass,
    val baseRisk: RiskLevel,
    val timeout: Duration,
    val maxOutputBytes: Long,
    val requiredCapabilities: Set<Capability>,
    val idempotency: Idempotency = Idempotency.NON_IDEMPOTENT,
    val executionTarget: ExecutionTargetType,
    val serverProvidedHints: Map<String, Boolean> = emptyMap(),
)

/**
 * The MCP tool source for ONE connected, user-enabled server (doc 10 section
 * 4.3). Converts each [McpToolSpec] into a [ToolDescriptor] named
 * `mcp.<serverId>.<toolName>` bound to an [ToolOrigin.McpOrigin].
 *
 * Invariants enforced at construction (fail-closed):
 * - [serverId] and every [McpToolSpec.serverToolName] are single name
 *   segments, so the full name has exactly 3 segments (stable, unambiguous,
 *   and parseable back into server + tool);
 * - the (serverId, serverToolName) pair is unique within this source;
 * - no [McpToolSpec] may declare [ToolOperationClass.READ_ONLY]
 *   (server hints are not classification, doc 02 section 7).
 */
class McpToolSource(
    private val serverId: String,
    private val protocolVersion: Int,
    private val specs: List<McpToolSpec>,
) : ToolSource {
    override val kind: ToolSourceKind = ToolSourceKind.MCP

    private val descriptors: List<ToolDescriptor> = specs.map { spec -> spec.toDescriptor() }

    init {
        requireSingleNameSegment(serverId, "MCP server id")
        requireNoDuplicates(descriptors)
        val seenTools = mutableSetOf<String>()
        specs.forEach { spec ->
            require(seenTools.add(spec.serverToolName)) {
                "duplicate MCP tool name within one server: ${spec.serverToolName}"
            }
        }
        descriptors.forEach { descriptor ->
            require(descriptor.operationClass != ToolOperationClass.READ_ONLY) {
                "MCP tool ${descriptor.name.value} must not be READ_ONLY (server hints are not classification)"
            }
        }
    }

    override fun load(): List<ToolDescriptor> = descriptors

    private fun McpToolSpec.toDescriptor(): ToolDescriptor {
        requireSingleNameSegment(serverToolName, "MCP tool name")
        return ToolDescriptor(
            name = ToolName("${ToolDescriptor.MCP_NAME_PREFIX}$serverId.$serverToolName"),
            version = version,
            description = description,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            operationClass = operationClass,
            baseRisk = baseRisk,
            timeout = timeout,
            maxOutputBytes = maxOutputBytes,
            requiredCapabilities = requiredCapabilities,
            idempotency = idempotency,
            executionTarget = executionTarget,
            origin = ToolOrigin.McpOrigin(serverId, protocolVersion, serverProvidedHints),
        )
    }

    private fun requireSingleNameSegment(
        value: String,
        label: String,
    ) {
        require(value.length in 1..MAX_NAME_SEGMENT_LENGTH) {
            "$label must be 1..$MAX_NAME_SEGMENT_LENGTH characters"
        }
        require(isAsciiLetterOrDigit(value.first())) { "$label must start with an ASCII letter or digit" }
        require(value.all { isAsciiLetterOrDigit(it) || it == '_' || it == '-' }) {
            "$label contains invalid characters"
        }
    }

    private fun isAsciiLetterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private companion object {
        const val MAX_NAME_SEGMENT_LENGTH = 64
    }
}

/** Fails when two descriptors share the same (name, version) identity. */
internal fun requireNoDuplicates(descriptors: List<ToolDescriptor>) {
    val seen = mutableSetOf<Pair<ToolName, ToolVersion>>()
    descriptors.forEach { descriptor ->
        val key = descriptor.name to descriptor.version
        require(key !in seen) { "duplicate tool registration: ${descriptor.name} v${descriptor.version}" }
        seen += key
    }
}
