package com.helix.app.chat

/** Exact built-in execution contracts; external names and content never select a projection policy. */
internal object ToolResultContract {
    val executionTools = setOf("bash", "code.linux.run", "skills.run")
    val capturedLogTools = executionTools + "code.linux.job.start"
    const val LOG_READ_TOOL = "code.linux.job.read_log"
}
