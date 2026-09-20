package com.helix.app.terminal

/** Application-only manual surface. No model tool registration and no passive Runtime binding. */
interface ManualTerminal {
    data class State(
        val sessionId: String,
        val phase: String,
        val workspace: String,
        val stopReason: String?,
        val exitStatus: Int?,
        val canSettle: Boolean,
    )

    data class Output(
        val cursor: String,
        val bytes: ByteArray,
        val gapBefore: Boolean,
        val eof: Boolean,
    )

    suspend fun hasSession(): Boolean

    suspend fun start(
        relativeDirectory: String = ".",
        leaseMs: Long = 7_200_000,
    ): State

    suspend fun query(): State

    suspend fun stop(): State

    suspend fun settle()

    suspend fun attach(): Connection

    interface Connection {
        suspend fun read(cursor: String?): Output

        suspend fun write(bytes: ByteArray): String

        suspend fun resize(
            rows: Int,
            columns: Int,
        )

        suspend fun detach()
    }
}
