package com.helix.app.chat

/** Exact built-in execution contracts; external names and content never select a projection policy. */
internal object ToolResultContract {
    val executionTools = setOf("bash", "code_linux_run", "skills_run")
    val capturedLogTools = executionTools + "code_linux_job_start"
    const val LOG_READ_TOOL = "code_linux_job_read_log"
}
