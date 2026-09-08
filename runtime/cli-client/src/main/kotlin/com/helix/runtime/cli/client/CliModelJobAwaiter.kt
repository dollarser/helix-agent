package com.helix.runtime.cli.client

import com.helix.runtime.cli.client.CliModelJobClient.AwaitOutcome

internal class CliModelJobAwaiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val transact: (Int) -> CliModelWireResult,
) {
    fun await(
        submitted: CliModelWireResult,
        timeoutMs: Long,
        pollIntervalMs: Long,
    ): AwaitOutcome =
        when {
            submitted.status != CliRuntimeProtocol.REPLY_JOB_ACCEPTED &&
                submitted.status != CliRuntimeProtocol.REPLY_JOB_DUPLICATE -> unavailable(submitted)

            submitted.record?.state?.terminal == true -> reconcile()

            else -> poll(timeoutMs, pollIntervalMs)
        }

    private fun poll(
        timeoutMs: Long,
        pollIntervalMs: Long,
    ): AwaitOutcome {
        val start = clock()
        var outcome: AwaitOutcome? = null
        while (outcome == null && clock() - start <= timeoutMs) {
            val queried = transact(CliRuntimeProtocol.TRANSACTION_JOB_QUERY)
            outcome =
                when {
                    queried.record?.state?.terminal == true -> reconcile()
                    queried.status != CliRuntimeProtocol.REPLY_JOB_STATE -> unavailable(queried)
                    else -> waitForNextPoll(pollIntervalMs)
                }
        }
        if (outcome == null) transact(CliRuntimeProtocol.TRANSACTION_JOB_CANCEL)
        return outcome ?: AwaitOutcome.TimedOut
    }

    private fun waitForNextPoll(pollIntervalMs: Long): AwaitOutcome? =
        try {
            pause(pollIntervalMs)
            null
        } catch (_: InterruptedException) {
            try {
                transact(CliRuntimeProtocol.TRANSACTION_JOB_CANCEL)
                AwaitOutcome.TimedOut
            } finally {
                Thread.currentThread().interrupt()
            }
        }

    private fun reconcile(): AwaitOutcome {
        val result = transact(CliRuntimeProtocol.TRANSACTION_JOB_FETCH_RESULT)
        return if (result.status == CliRuntimeProtocol.REPLY_JOB_STATE && result.record != null) {
            AwaitOutcome.Terminal(result.record, result.events)
        } else {
            unavailable(result)
        }
    }

    private fun unavailable(result: CliModelWireResult) =
        AwaitOutcome.Unavailable(result.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
}
