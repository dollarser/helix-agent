package com.helix.app.proot

/** A successful start is only acceptance; a terminal observation and host settlement are separate facts. */
internal object DetachedCommandProjection {
    fun state(
        facts: CommandResultFacts,
        accepted: Boolean,
        terminal: DetachedCommandFacts?,
        fallback: () -> CommandDetailState,
    ): CommandDetailState =
        when (terminal?.state) {
            "SUCCEEDED" -> {
                CommandDetailState.SUCCEEDED
            }

            "FAILED", "TIMED_OUT", "INPUT_INVALID", "OUTPUT_LIMIT_EXCEEDED" -> {
                CommandDetailState.FAILED
            }

            "CANCELLED" -> {
                CommandDetailState.CANCELLED
            }

            null -> {
                when {
                    accepted -> CommandDetailState.SUBMITTED
                    facts.callState == "COMPLETED" -> CommandDetailState.UNKNOWN
                    else -> fallback()
                }
            }

            else -> {
                CommandDetailState.UNKNOWN
            }
        }

    fun pending(
        toolName: String,
        callState: String,
        accepted: Boolean,
        terminal: DetachedCommandFacts?,
    ): Boolean =
        toolName == "code.linux.job.start" && terminal?.settled != true &&
            (terminal != null || accepted || callState in setOf("NEEDS_REVIEW", "INTERRUPTED"))
}
