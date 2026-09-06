package com.helix.runtime.cli.client

/** Shared, versioned cross-APK contract bundled by the developer app and subscription Runtime. */
object CliRuntimeProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "com.helix.runtime.cli.ICliRuntimeService/1"
    const val PERMISSION = "com.helix.permission.BIND_CLI_RUNTIME"
    const val RUNTIME_PACKAGE = "com.helix.runtime.cli"
    const val SERVICE_CLASS = "com.helix.runtime.cli.app.CliRuntimeService"
    const val CODEX_LOGIN_ACTIVITY = "com.helix.runtime.cli.app.CodexLoginActivity"
    const val TRANSACTION_STATUS = 1
    const val TRANSACTION_JOB_SUBMIT = 2
    const val TRANSACTION_JOB_QUERY = 3
    const val TRANSACTION_JOB_CANCEL = 4
    const val TRANSACTION_JOB_RECONCILE = 5
    const val TRANSACTION_DEBUG_SELF_KILL = 1_000
    const val REPLY_OK = 0
    const val REPLY_CALLER_MISMATCH = 1
    const val REPLY_JOB_ACCEPTED = 10
    const val REPLY_JOB_DUPLICATE = 11
    const val REPLY_JOB_STATE = 12
    const val REPLY_JOB_NOT_FOUND = 13
    const val REPLY_JOB_REQUEST_MISMATCH = 14
    const val REPLY_JOB_BUSY = 15
    const val REPLY_JOB_JOURNAL_FULL = 16
    const val REPLY_JOB_INVALID = 17
    const val MAX_STATUS_BYTES = 16 * 1024
    const val BIND_DEADLINE_MS = 20_000L
    const val FIXED_CODEX_SMOKE_SHA256 = "85b37fcc8eca5da536023a7628056aa43469a6ee71b29920ee20ec38d8a80d02"
    val MAIN_APP_PACKAGES = setOf("com.helix.agent", "com.helix.agent.developer")
}
