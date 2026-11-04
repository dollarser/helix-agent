package com.helix.extensions.mobileuse.automation.backend

import java.util.concurrent.TimeUnit

/** Wait for the shell acknowledgement, not the target app's first frame. Original-call guard owns the deadline. */
internal fun awaitLaunchAcknowledgement(
    process: Process,
    allowed: () -> Boolean,
): Boolean {
    while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (!allowed()) return false
    }
    return allowed()
}

/** Android prints the intent before dispatch; explicit errors must override that prefix. */
internal fun launchAcknowledged(
    exitCode: Int,
    output: String,
): Boolean =
    exitCode == 0 && output.lineSequence().any { it.startsWith("Starting: Intent {") } &&
        output.lineSequence().none { line ->
            val text = line.trimStart()
            text.startsWith("Error") || text.startsWith("Exception") ||
                text.substringBefore(':').endsWith("Exception")
        }

internal fun launchArguments(
    displayId: Int,
    component: String,
): List<String> =
    listOf(
        "/system/bin/am",
        "start",
        "--display",
        displayId.toString(),
        "--user",
        "current",
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
        "-n",
        component,
    )
