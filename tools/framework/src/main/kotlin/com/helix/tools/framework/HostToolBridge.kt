package com.helix.tools.framework

/** Ephemeral transport credentials, not authorization. Never include the proof in logs or tool arguments. */
class ToolBridgeEndpoint(
    val socketName: String,
    val proof: String,
) {
    override fun toString(): String = "ToolBridgeEndpoint(redacted)"
}

/** Host-created, original-call capability. Every request still enters the normal tool gateway. */
interface HostToolBridge : AutoCloseable {
    val endpoint: ToolBridgeEndpoint
    val executionDeadline: java.time.Instant
    val retainingExecution: Boolean get() = false
    val reviewRequired: Boolean get() = false
    val unresolvedCallIds: Set<String> get() = emptySet()

    fun request(json: String): String

    /** Called by the trusted execution client after reading the bound service's own process identity. */
    fun bindPeer(
        pid: Int,
        uid: Int,
    )

    fun acceptsPeer(
        pid: Int,
        uid: Int,
    ): Boolean

    /** Runtime job identity, never supplied by generated code or Skill metadata. */
    fun bindJob(
        jobId: String,
        detached: Boolean,
        deadline: java.time.Instant,
    ) = Unit
}

fun interface HostToolBridgeProvider {
    fun open(call: ExecutableToolCall): HostToolBridge
}
