package com.helix.extensions.plugin

/** A reference to a real native resource, not execution capacity or a second task registry. */
@Suppress("TooGenericExceptionCaught") // Record cleanup failures, including fatal errors, then rethrow unchanged.
class NativeResource<T : Any>(
    val value: T,
    private val cleanup: (T) -> Unit,
) : AutoCloseable {
    enum class State { ACTIVE, DRAINING, CLEANING, CLOSED, CLEANUP_FAILED }

    private val lock = Any()
    private var references = 0
    private var current = State.ACTIVE
    private var cleanupFailure: Throwable? = null

    val state: State get() = synchronized(lock) { current }
    val failure: Throwable? get() = synchronized(lock) { cleanupFailure }

    fun borrow(): Lease<T>? =
        synchronized(lock) {
            if (current != State.ACTIVE) {
                null
            } else {
                references++
                lease()
            }
        }

    private fun lease(): Lease<T> = Lease(value, ::release, ::retain) { state == State.ACTIVE }

    private fun retain(): Lease<T> =
        synchronized(lock) {
            check(references > 0 && current in setOf(State.ACTIVE, State.DRAINING))
            references++
            lease()
        }

    override fun close() {
        val shouldClean =
            synchronized(lock) {
                if (current == State.CLEANUP_FAILED) throw requireNotNull(cleanupFailure)
                if (current == State.ACTIVE) current = State.DRAINING
                beginCleanup()
            }
        if (shouldClean) clean()
    }

    private fun release() {
        val shouldClean =
            synchronized(lock) {
                check(references > 0)
                references--
                beginCleanup()
            }
        if (shouldClean) clean()
    }

    private fun beginCleanup(): Boolean {
        if (current != State.DRAINING || references != 0) return false
        current = State.CLEANING
        return true
    }

    private fun clean() {
        try {
            cleanup(value)
            synchronized(lock) { current = State.CLOSED }
        } catch (error: Throwable) {
            synchronized(lock) {
                cleanupFailure = error
                current = State.CLEANUP_FAILED
            }
            throw error
        }
    }

    class Lease<T : Any> internal constructor(
        val value: T,
        private val release: () -> Unit,
        private val retain: () -> Lease<T>,
        private val admitting: () -> Boolean,
    ) : AutoCloseable {
        private val lock = Any()
        private var closed = false
        val accepting: Boolean get() = synchronized(lock) { !closed && admitting() }

        fun retain(): Lease<T> =
            synchronized(lock) {
                check(!closed)
                retain.invoke()
            }

        override fun close() {
            val released =
                synchronized(lock) {
                    if (closed) {
                        false
                    } else {
                        closed = true
                        true
                    }
                }
            if (released) release()
        }
    }
}
