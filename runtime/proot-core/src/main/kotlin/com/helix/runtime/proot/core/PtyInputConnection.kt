package com.helix.runtime.proot.core

import java.util.UUID

/**
 * One process-local manual writer for one PTY generation. The trusted session service must first
 * authorize the user and resolve the original session; a connection is not execution authority.
 * Detach revokes future submissions, but does not undo accepted input or terminate the shell.
 * The single native writer keeps draining accepted chunks, including across detach/attach.
 */
class PtyInputConnection {
    enum class Admission { ACCEPTED, FULL, DETACHED, CLOSED }

    private val input = PtyInputBuffer()
    private var writer: String? = null
    private var closed = false

    /** No takeover: a second view must wait for the current connection to detach. */
    @Synchronized
    fun attach(): String? {
        if (closed || writer != null) return null
        return UUID.randomUUID().toString().also { writer = it }
    }

    /** A delayed disconnect from an old view cannot revoke its successor's connection. */
    @Synchronized
    fun detach(connection: String): Boolean {
        if (writer != connection) return false
        writer = null
        return true
    }

    /** Validation and queue admission are atomic with detach/close. Never retry uncertain input. */
    @Synchronized
    fun offer(
        connection: String,
        bytes: ByteArray,
    ): Admission =
        when {
            closed -> {
                Admission.CLOSED
            }

            writer != connection -> {
                Admission.DETACHED
            }

            else -> {
                when (input.offer(bytes)) {
                    PtyInputBuffer.Admission.ACCEPTED -> Admission.ACCEPTED
                    PtyInputBuffer.Admission.FULL -> Admission.FULL
                    PtyInputBuffer.Admission.CLOSED -> error("Input closed outside its connection owner")
                }
            }
        }

    /** Runtime's sole native writer only; a UI connection never polls or claims execution success. */
    @Synchronized
    fun poll(): ByteArray? = input.poll()

    /** Reject all connections and report queued bytes discarded, excluding the writer's in-flight chunk. */
    @Synchronized
    fun close(): Int {
        closed = true
        writer = null
        return input.close()
    }
}
