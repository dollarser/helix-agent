package com.helix.runtime.cli.app

/** Fixed protocol checkpoints only, never request/response bodies or credentials. */
internal object AntigravityWireDiagnostic {
    enum class Event {
        START,
        ENCODED,
        HTTP_OK,
        HTTP_FAILED,
        INVALID_FRAME,
        SERVER_ERROR,
        INCOMPLETE,
        EXCEPTION,
        TOKEN_LIMIT_WITH_CONTENT,
        TOKEN_LIMIT_EMPTY,
        COMPLETED,
    }

    var sink: (Event) -> Unit = {}

    fun report(event: Event) = sink(event)
}
