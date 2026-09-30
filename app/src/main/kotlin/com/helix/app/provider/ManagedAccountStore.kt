package com.helix.app.provider

import com.helix.app.internal.LineStore

/** Revision is a random login-lifetime identifier, never an account name, token, or token hash. */
data class ManagedAccountSnapshot(
    val state: State,
    val revision: String? = null,
) {
    enum class State { UNKNOWN, LOGGED_IN, LOGGED_OUT, CREDENTIAL_ERROR, UNAVAILABLE }

    init {
        require(state != State.LOGGED_IN || revision != null)
        require(revision == null || Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(revision))
    }

    val ready: Boolean get() = state == State.LOGGED_IN

    fun invalidates(previous: ManagedAccountSnapshot): Boolean =
        when (state) {
            State.LOGGED_IN -> revision != previous.revision
            State.LOGGED_OUT, State.CREDENTIAL_ERROR -> this != previous
            State.UNKNOWN, State.UNAVAILABLE -> false
        }
}

class ManagedAccountStore(
    private val store: LineStore,
) {
    fun read(id: String): ManagedAccountSnapshot =
        runCatching {
            val lines = store.lines("provider-account-$id")
            if (lines.isEmpty()) return ManagedAccountSnapshot(ManagedAccountSnapshot.State.UNKNOWN)
            require(lines.size == 2)
            ManagedAccountSnapshot(ManagedAccountSnapshot.State.valueOf(lines[0]), lines[1].takeUnless { it == "-" })
        }.getOrElse { ManagedAccountSnapshot(ManagedAccountSnapshot.State.UNKNOWN) }

    fun save(
        id: String,
        value: ManagedAccountSnapshot,
    ) {
        store.setLines("provider-account-$id", listOf(value.state.name, value.revision ?: "-"))
    }
}
