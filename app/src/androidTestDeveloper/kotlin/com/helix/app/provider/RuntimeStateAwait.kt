package com.helix.app.provider

import android.os.SystemClock
import com.helix.runtime.cli.client.CliModelJobClient

/** Wait for query-only cold rebind after real process death; never re-submit a job. */
internal fun awaitRuntimeState(read: () -> CliModelJobClient.StateOutcome): CliModelJobClient.StateOutcome.Ok {
    val deadline = SystemClock.elapsedRealtime() + 10_000
    var result = read()
    while (result is CliModelJobClient.StateOutcome.Unavailable && SystemClock.elapsedRealtime() < deadline) {
        Thread.sleep(50)
        result = read()
    }
    check(result is CliModelJobClient.StateOutcome.Ok) { "Runtime read did not recover: $result" }
    return result
}
