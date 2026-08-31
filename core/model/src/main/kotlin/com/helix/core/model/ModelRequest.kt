package com.helix.core.model

import com.helix.core.model.internal.JsonNode
import com.helix.core.model.internal.parseJson

/**
 * Role of one model request message (doc 02 section 6.1, doc 10 section 2.1: all vendor
 * protocols normalize into the internal contract before the Agent Loop sees anything).
 */
enum class ModelRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * Vendor-neutral reasoning preference. Adapters map this onto their native knob
 * (e.g. OpenAI `reasoning_effort`, Anthropic thinking budget) or drop it when the capability
 * snapshot says `reasoning=false` (doc 10 section 2.4).
 */
enum class ReasoningEffort {
    OFF,
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Opaque reference to an image the model may see (vision). The bytes live in the content
 * store and are resolved by the adapter/transport layer — the contract never carries inline
 * image data (doc 02 section 4: Agent Core never touches vendor DTOs or raw payloads).
 *
 * [mediaType] is a closed allowlist; unknown types are rejected at construction (fail closed).
 */
data class ImageReference(
    val ref: ArtifactRef,
    val mediaType: String,
) {
    init {
        require(mediaType in MEDIA_TYPES) { "image mediaType is not supported: $mediaType" }
    }

    internal companion object {
        val MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}

/**
 * One message of a model request. Role/content rules (enforced at construction):
 *
 * - [text] is non-blank for every role; NUL is rejected (other C0 characters such as newlines
 *   are legitimate text, e.g. code blocks);
 * - [images] are only allowed on [ModelRole.USER] (tool results and assistant/system turns
 *   carry no images in the first version);
 * - a [ModelRole.TOOL] message answers exactly one call: [toolCallId] and [toolName] are
 *   mandatory (the vendor APIs key tool results by the call id — doc 02 section 5.3);
 * - all other roles must leave [toolCallId]/[toolName] null.
 */
data class ModelMessage(
    val role: ModelRole,
    val text: String,
    val images: List<ImageReference> = emptyList(),
    val toolCallId: ToolCallId? = null,
    val toolName: ToolName? = null,
) {
    init {
        require(text.isNotBlank()) { "message text must not be blank" }
        require(text.none { it == '\u0000' }) { "message text must not contain NUL" }
        require(text.length <= MAX_TEXT_LENGTH) { "message text exceeds $MAX_TEXT_LENGTH chars" }
        require(images.size <= MAX_IMAGES_PER_MESSAGE) {
            "at most $MAX_IMAGES_PER_MESSAGE images per message"
        }
        when (role) {
            ModelRole.TOOL -> {
                require(toolCallId != null) { "a tool result message must carry the toolCallId" }
                require(toolName != null) { "a tool result message must carry the toolName" }
            }

            else -> {
                require(toolCallId == null && toolName == null) {
                    "only tool result messages may carry toolCallId/toolName"
                }
            }
        }
        if (role != ModelRole.USER) {
            require(images.isEmpty()) { "only user messages may carry images" }
        }
    }

    internal companion object {
        const val MAX_TEXT_LENGTH = 262_144
        const val MAX_IMAGES_PER_MESSAGE = 4
    }
}

/**
 * A tool offered to the model in this request (filtered by mode/capability/scope before
 * building — doc 02 section 5.1: unavailable tools never enter the model tool table).
 *
 * [inputSchemaJson] must be a JSON object in canonical form (ADR-0001 decode discipline: the
 * shared strict parser is the gate); adapters re-encode it into their vendor shape.
 */
data class ModelToolSchema(
    val name: ToolName,
    val description: String,
    val inputSchemaJson: String,
) {
    init {
        require(description.isNotBlank()) { "tool description must not be blank" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "tool description exceeds $MAX_DESCRIPTION_LENGTH chars"
        }
        require(description.none { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }) {
            "tool description contains a control character"
        }
        require(inputSchemaJson.isNotBlank()) { "inputSchemaJson must not be blank" }
        val parsed = parseJson(inputSchemaJson)
        require(parsed is JsonNode.Obj) { "inputSchemaJson must be a JSON object" }
        require(parsed.entries.size <= MAX_SCHEMA_ENTRIES) {
            "input schema has too many entries (max $MAX_SCHEMA_ENTRIES)"
        }
    }

    internal companion object {
        const val MAX_DESCRIPTION_LENGTH = 1024
        const val MAX_SCHEMA_ENTRIES = 256
    }
}

/**
 * The vendor-neutral model request (doc 02 section 4 `ModelProvider.stream(request)`,
 * doc 10 section 2.1). Pure data: no secrets, no provider configuration, no transport
 * concerns — the adapter resolves credentials from the SecretStore (HXA-020) and applies
 * its protocol encoding (HXA-022/023/024).
 *
 * Invariants (all fail closed at construction):
 * - [model] is a non-blank model id without whitespace/control characters;
 * - [messages] is 1..[MAX_MESSAGES] and the **last** message is a [ModelRole.USER] or
 *   [ModelRole.TOOL] message (a request must never end on an assistant/system turn);
 * - [tools] is 0..[MAX_TOOLS] with unique names;
 * - [temperature] (when set) is a finite value in 0.0..2.0;
 * - [maxOutputTokens] (when set) is at least 1;
 * - [stopSequences] is at most 4 bounded, control-character-free strings;
 * - [seed] is an optional reproducibility hint;
 * - [reasoning] defaults to [ReasoningEffort.OFF].
 */
data class ModelRequest(
    val model: String,
    val messages: List<ModelMessage>,
    val tools: List<ModelToolSchema> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Long? = null,
    val seed: Long? = null,
    val stopSequences: List<String> = emptyList(),
    val reasoning: ReasoningEffort = ReasoningEffort.OFF,
) {
    init {
        require(model.isNotBlank() && model.length <= MAX_MODEL_LENGTH) {
            "model must be 1..$MAX_MODEL_LENGTH non-blank chars"
        }
        require(model.none { it.isWhitespace() || it.code in 0x00..0x1F || it.code in 0x7F..0x9F }) {
            "model contains a control character"
        }
        require(messages.isNotEmpty()) { "a model request needs at least one message" }
        require(messages.size <= MAX_MESSAGES) { "at most $MAX_MESSAGES messages per request" }
        val lastRole = messages.last().role
        require(lastRole == ModelRole.USER || lastRole == ModelRole.TOOL) {
            "the last message must be a user or tool message, was: $lastRole"
        }
        require(tools.size <= MAX_TOOLS) { "at most $MAX_TOOLS tools per request" }
        val names = tools.map { it.name.value }
        require(names.toSet().size == names.size) { "duplicate tool name in request tools" }
        temperature?.let { t ->
            require(t.isFinite()) { "temperature must be finite" }
            require(t in 0.0..2.0) { "temperature must be in 0.0..2.0: $t" }
        }
        maxOutputTokens?.let { n -> require(n >= 1) { "maxOutputTokens must be >= 1" } }
        require(stopSequences.size <= MAX_STOP_SEQUENCES) {
            "at most $MAX_STOP_SEQUENCES stop sequences"
        }
        stopSequences.forEach { s ->
            require(s.isNotBlank() && s.length <= MAX_STOP_SEQUENCE_LENGTH) {
                "stop sequence must be 1..$MAX_STOP_SEQUENCE_LENGTH non-blank chars"
            }
            require(s.none { c -> c.code in 0x00..0x1F || c.code in 0x7F..0x9F }) {
                "stop sequence contains a control character"
            }
        }
    }

    companion object {
        const val MAX_MODEL_LENGTH = 256
        const val MAX_MESSAGES = 512
        const val MAX_TOOLS = 64
        const val MAX_STOP_SEQUENCES = 4
        const val MAX_STOP_SEQUENCE_LENGTH = 64
    }
}
