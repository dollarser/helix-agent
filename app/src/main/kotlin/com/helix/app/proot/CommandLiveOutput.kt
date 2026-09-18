package com.helix.app.proot

/** User-visible preview only. Never persisted as a message or used as a tool result. */
internal data class CommandLiveOutput(
    val stdout: String = "",
    val stderr: String = "",
    val truncated: Boolean = false,
    val unavailable: Boolean = false,
)

/** Application observation entry; flavor seam keeps Runtime types out of consumer. */
internal object CommandLogObserver {
    fun observe(binding: CommandJobBindingFacts) = ProotToolModule.observeCommandLog(binding)
}
