package com.helix.app.provider

import com.helix.app.AppContainer
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicBoolean

internal class CliGoalBoundaryVerifier(
    private val container: AppContainer,
    private val successful: Boolean,
    private val resultHeld: AtomicBoolean,
) {
    fun verify(
        client: CliModelJobClient,
        job: String,
        call: String,
    ) {
        val expected = if (successful) CliModelJobState.SUCCEEDED else CliModelJobState.RUNNING
        await {
            (client.query(job) as? CliModelJobClient.StateOutcome.Ok)?.record?.state == expected &&
                (!successful || resultHeld.get())
        }
        if (successful) {
            val record = (client.query(job) as CliModelJobClient.StateOutcome.Ok).record
            org.junit.Assert.assertNotNull(record.reconciledAtEpochMillis)
            assertEquals(
                record.outputSha256,
                container.storage.artifacts
                    .resolve("cli-result-$call")
                    .sha256,
            )
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "CLI Goal boundary not reached" }
            Thread.sleep(25)
        }
    }
}
