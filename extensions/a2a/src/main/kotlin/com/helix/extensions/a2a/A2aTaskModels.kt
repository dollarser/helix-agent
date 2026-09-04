package com.helix.extensions.a2a

import kotlinx.serialization.json.JsonElement

data class A2aOutboundArtifact(
    val filename: String,
    val mediaType: String,
    val base64: String,
    val sha256: String,
)

data class A2aTaskSubmission(
    val messageId: String,
    val task: String,
    val data: JsonElement? = null,
    val artifacts: List<A2aOutboundArtifact> = emptyList(),
    val acceptedOutputModes: List<String> = emptyList(),
)

enum class A2aRemoteTaskState {
    SUBMITTED,
    WORKING,
    INPUT_REQUIRED,
    AUTH_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED,
    UNKNOWN,
    DIRECT_MESSAGE,
}

sealed interface A2aRemotePart {
    data class Text(
        val text: String,
    ) : A2aRemotePart

    data class Data(
        val data: JsonElement,
    ) : A2aRemotePart

    data class Raw(
        val base64: String,
        val filename: String?,
        val mediaType: String?,
    ) : A2aRemotePart

    data class Url(
        val url: String,
        val filename: String?,
        val mediaType: String?,
    ) : A2aRemotePart
}

data class A2aRemoteArtifact(
    val artifactId: String,
    val name: String?,
    val parts: List<A2aRemotePart>,
)

data class A2aTaskUpdate(
    val taskId: String?,
    val contextId: String?,
    val state: A2aRemoteTaskState,
    val parts: List<A2aRemotePart>,
    val artifacts: List<A2aRemoteArtifact>,
    val sequence: Long,
    /** Opaque SSE id used only as Last-Event-ID when resubscribing to this exact Task. */
    val eventId: String?,
    val final: Boolean,
)

interface A2aTaskStreamListener {
    fun onUpdate(update: A2aTaskUpdate)

    fun onClosed()

    fun onFailure(failure: A2aTransportFailure)
}

fun interface A2aTaskStreamHandle {
    fun cancel()
}

/** [requestMayHaveArrived] is false only when no request bytes could have reached the peer. */
class A2aTransportFailure(
    message: String,
    val requestMayHaveArrived: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

interface A2aTaskClient {
    fun send(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        submission: A2aTaskSubmission,
    ): A2aTaskUpdate

    fun sendStreaming(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        submission: A2aTaskSubmission,
        listener: A2aTaskStreamListener,
    ): A2aTaskStreamHandle

    fun getTask(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        taskId: String,
    ): A2aTaskUpdate

    fun cancelTask(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        taskId: String,
    ): A2aTaskUpdate

    fun subscribeToTask(
        interfaceSnapshot: A2aInterfaceSnapshot,
        bearer: String?,
        taskId: String,
        lastEventId: String?,
        listener: A2aTaskStreamListener,
    ): A2aTaskStreamHandle
}
