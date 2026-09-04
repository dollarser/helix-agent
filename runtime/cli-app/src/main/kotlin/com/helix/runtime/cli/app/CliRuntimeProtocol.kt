package com.helix.runtime.cli.app

object CliRuntimeProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "com.helix.runtime.cli.ICliRuntimeService/1"
    const val PERMISSION = "com.helix.permission.BIND_CLI_RUNTIME"
    const val TRANSACTION_STATUS = 1
    const val REPLY_OK = 0
    const val REPLY_CALLER_MISMATCH = 1
    const val MAX_STATUS_BYTES = 16 * 1024
    val MAIN_APP_PACKAGES = setOf("com.helix.agent", "com.helix.agent.developer")
}
