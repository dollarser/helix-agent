package com.helix.runtime.cli.client

/** Shared, versioned cross-APK contract bundled by the developer app and subscription Runtime. */
object CliRuntimeProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "com.helix.runtime.cli.ICliRuntimeService/1"
    const val PERMISSION = "com.helix.permission.BIND_CLI_RUNTIME"
    const val RUNTIME_PACKAGE = "com.helix.runtime.cli"
    const val SERVICE_CLASS = "com.helix.runtime.cli.app.CliRuntimeService"
    const val TRANSACTION_STATUS = 1
    const val REPLY_OK = 0
    const val REPLY_CALLER_MISMATCH = 1
    const val MAX_STATUS_BYTES = 16 * 1024
    const val BIND_DEADLINE_MS = 20_000L
    val MAIN_APP_PACKAGES = setOf("com.helix.agent", "com.helix.agent.developer")
}
