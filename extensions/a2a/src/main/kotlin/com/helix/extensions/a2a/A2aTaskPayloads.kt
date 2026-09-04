package com.helix.extensions.a2a

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Suppress("TooManyFunctions") // bounded codec helpers are kept together to share one wire-limit contract
internal object A2aTaskPayloads {
    fun sendParams(
        submission: A2aTaskSubmission,
        tenant: String?,
    ): JsonObject {
        require(submission.messageId.isNotBlank() && submission.messageId.length <= 256) { "A2A message id is invalid" }
        require(submission.task.isNotBlank() && submission.task.toByteArray().size <= MAX_TASK_BYTES) {
            "A2A task text is empty or oversized"
        }
        require(submission.artifacts.size <= MAX_OUTBOUND_ARTIFACTS) { "too many A2A outbound artifacts" }
        require(submission.acceptedOutputModes.size <= MAX_MODES) { "too many A2A output modes" }
        return buildJsonObject {
            tenant?.let { put("tenant", it) }
            put(
                "message",
                buildJsonObject {
                    put("messageId", submission.messageId)
                    put("role", "ROLE_USER")
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", submission.task) })
                            submission.data?.let { add(buildJsonObject { put("data", it) }) }
                            submission.artifacts.forEach { artifact ->
                                require(
                                    artifact.base64.length <= MAX_BASE64_CHARS,
                                ) { "A2A outbound artifact exceeds limit" }
                                add(
                                    buildJsonObject {
                                        put("raw", artifact.base64)
                                        put("filename", artifact.filename)
                                        put("mediaType", artifact.mediaType)
                                        put("metadata", buildJsonObject { put("sha256", artifact.sha256) })
                                    },
                                )
                            }
                        },
                    )
                },
            )
            if (submission.acceptedOutputModes.isNotEmpty()) {
                put(
                    "configuration",
                    buildJsonObject {
                        put(
                            "acceptedOutputModes",
                            buildJsonArray { submission.acceptedOutputModes.forEach { add(JsonPrimitive(it)) } },
                        )
                    },
                )
            }
        }
    }

    fun taskParams(
        taskId: String,
        tenant: String?,
    ): JsonObject {
        require(taskId.isNotBlank() && taskId.length <= 512) { "A2A Task id is invalid" }
        return buildJsonObject {
            tenant?.let { put("tenant", it) }
            put("id", taskId)
        }
    }

    fun unwrapJsonRpc(root: JsonObject): JsonObject {
        require(root["error"] == null) { "A2A JSON-RPC response contains an error" }
        return root["result"] as? JsonObject ?: error("A2A JSON-RPC response has no object result")
    }

    @Suppress("CyclomaticComplexMethod") // A2A response/event variants normalize into one closed update model
    fun parseUpdate(
        value: JsonObject,
        sequence: Long,
        eventId: String? = null,
    ): A2aTaskUpdate {
        val body =
            (value["task"] as? JsonObject)
                ?: (value["message"] as? JsonObject)
                ?: (value["statusUpdate"] as? JsonObject)
                ?: (value["artifactUpdate"] as? JsonObject)
                ?: value
        val direct = value["message"] != null || (body["role"] != null && body["parts"] != null && body["id"] == null)
        val taskId = body.stringOrNull("id") ?: body.stringOrNull("taskId")
        val contextId = body.stringOrNull("contextId")
        val status = body["status"] as? JsonObject
        val state = if (direct) A2aRemoteTaskState.DIRECT_MESSAGE else parseState(status?.stringOrNull("state"))
        val message = (status?.get("message") as? JsonObject) ?: if (direct) body else null
        val parts = message?.partsOrEmpty() ?: emptyList()
        val listedArtifacts =
            (body["artifacts"] as? JsonArray)
                ?.takeBounded(MAX_REMOTE_ARTIFACTS, "artifacts")
                ?.map { value -> (value as? JsonObject ?: error("A2A Artifact must be an object")).asArtifact() }
                ?: emptyList()
        val eventArtifact = (body["artifact"] as? JsonObject)?.let { listOf(it.asArtifact()) } ?: emptyList()
        val artifacts = listedArtifacts + eventArtifact
        require(parts.size + artifacts.sumOf { it.parts.size } <= MAX_TOTAL_PARTS) { "A2A response has too many parts" }
        return A2aTaskUpdate(
            taskId = taskId,
            contextId = contextId,
            state = state,
            parts = parts,
            artifacts = artifacts,
            sequence = sequence,
            eventId = eventId,
            final = state in TERMINAL_STATES,
        )
    }

    private fun JsonObject.asArtifact(): A2aRemoteArtifact =
        A2aRemoteArtifact(
            artifactId = requiredString("artifactId", 512),
            name = stringOrNull("name")?.take(MAX_NAME_LENGTH),
            parts = partsOrEmpty(),
        )

    private fun JsonObject.partsOrEmpty(): List<A2aRemotePart> =
        (this["parts"] as? JsonArray)
            ?.takeBounded(MAX_TOTAL_PARTS, "parts")
            ?.map { it.asPart() }
            ?: emptyList()

    private fun JsonElement.asPart(): A2aRemotePart {
        val part = this as? JsonObject ?: error("A2A Part must be an object")
        val contents =
            listOf("text", "data", "raw", "url").filter { key ->
                part[key] != null && part[key] !is JsonNull
            }
        require(contents.size == 1) { "A2A Part must contain exactly one supported content field" }
        val filename = part.stringOrNull("filename")?.take(MAX_NAME_LENGTH)
        val mediaType = part.stringOrNull("mediaType")?.take(MAX_MEDIA_TYPE_LENGTH)
        return when (val key = contents.single()) {
            "text" -> {
                A2aRemotePart.Text(part.requiredString(key, MAX_TEXT_LENGTH))
            }

            "data" -> {
                A2aRemotePart.Data(checkNotNull(part[key]))
            }

            "raw" -> {
                val raw = part.requiredString(key, MAX_BASE64_CHARS)
                A2aRemotePart.Raw(raw, filename, mediaType)
            }

            "url" -> {
                A2aRemotePart.Url(part.requiredString(key, MAX_URL_LENGTH), filename, mediaType)
            }

            else -> {
                error("unsupported A2A Part")
            }
        }
    }

    private fun parseState(value: String?): A2aRemoteTaskState =
        when (value) {
            "TASK_STATE_SUBMITTED" -> A2aRemoteTaskState.SUBMITTED
            "TASK_STATE_WORKING" -> A2aRemoteTaskState.WORKING
            "TASK_STATE_INPUT_REQUIRED" -> A2aRemoteTaskState.INPUT_REQUIRED
            "TASK_STATE_AUTH_REQUIRED" -> A2aRemoteTaskState.AUTH_REQUIRED
            "TASK_STATE_COMPLETED" -> A2aRemoteTaskState.COMPLETED
            "TASK_STATE_FAILED" -> A2aRemoteTaskState.FAILED
            "TASK_STATE_CANCELED" -> A2aRemoteTaskState.CANCELLED
            "TASK_STATE_REJECTED" -> A2aRemoteTaskState.REJECTED
            else -> A2aRemoteTaskState.UNKNOWN
        }

    private fun JsonObject.requiredString(
        key: String,
        maxLength: Int,
    ): String = stringOrNull(key)?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: error("A2A $key is invalid")

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray.takeBounded(
        max: Int,
        label: String,
    ): JsonArray {
        require(size <= max) { "A2A $label exceeds $max entries" }
        return this
    }

    private val TERMINAL_STATES =
        setOf(
            A2aRemoteTaskState.COMPLETED,
            A2aRemoteTaskState.FAILED,
            A2aRemoteTaskState.CANCELLED,
            A2aRemoteTaskState.REJECTED,
            A2aRemoteTaskState.DIRECT_MESSAGE,
        )
    private const val MAX_TASK_BYTES = 256 * 1024
    private const val MAX_OUTBOUND_ARTIFACTS = 4
    private const val MAX_REMOTE_ARTIFACTS = 32
    private const val MAX_TOTAL_PARTS = 128
    private const val MAX_MODES = 16
    private const val MAX_BASE64_CHARS = 3 * 1024 * 1024
    private const val MAX_TEXT_LENGTH = 512 * 1024
    private const val MAX_NAME_LENGTH = 256
    private const val MAX_MEDIA_TYPE_LENGTH = 256
    private const val MAX_URL_LENGTH = 2_048
}
