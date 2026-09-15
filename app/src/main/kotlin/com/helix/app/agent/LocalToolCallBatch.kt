package com.helix.app.agent

/** Provider IDs identify protocol messages, never globally unique local execution/approval rows. */
internal class LocalToolCallBatch(
    wireCalls: List<BufferedModelToolCall>,
    idGenerator: () -> String,
) {
    val calls = wireCalls.map { it.copy(callId = idGenerator()) }
    private val wireIds = calls.zip(wireCalls).associate { (local, wire) -> local.callId to wire.callId }

    init {
        require(calls.all { it.callId.isNotBlank() })
        require(wireIds.size == calls.size) { "Local tool IDs must be unique" }
    }

    fun wireId(localId: String): String = wireIds.getValue(localId)
}
