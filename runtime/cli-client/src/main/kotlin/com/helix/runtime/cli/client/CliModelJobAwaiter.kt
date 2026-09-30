package com.helix.runtime.cli.client

import com.helix.runtime.cli.client.CliModelJobClient.AwaitOutcome

internal class CliModelJobAwaiter(
    private val clock: () -> Long = {
        java.util.concurrent.TimeUnit.NANOSECONDS
            .toMillis(System.nanoTime())
    },
    private val pause: (Long) -> Unit = Thread::sleep,
    private val readProgress: () -> Unit = {},
    private val transact: (Int) -> CliModelWireResult,
) {
    fun await(
        submitted: CliModelWireResult,
        timeoutMs: Long,
        pollIntervalMs: Long,
    ): AwaitOutcome {
        require(timeoutMs >= 0 && pollIntervalMs > 0)
        val record = submitted.record
        return when {
            submitted.status != CliRuntimeProtocol.REPLY_JOB_ACCEPTED &&
                submitted.status != CliRuntimeProtocol.REPLY_JOB_DUPLICATE -> unavailable(submitted)

            record == null -> unavailable(submitted)

            record.state.terminal -> reconcile(record)

            else -> poll(timeoutMs, pollIntervalMs, record)
        }
    }

    private fun poll(
        timeoutMs: Long,
        pollIntervalMs: Long,
        original: CliModelJobRecord,
    ): AwaitOutcome {
        val start = clock()
        var outcome: AwaitOutcome? = null
        while (outcome == null && (timeoutMs <= 0L || clock() - start <= timeoutMs)) {
            val queried = transact(CliRuntimeProtocol.TRANSACTION_JOB_QUERY)
            outcome =
                when {
                    queried.status != CliRuntimeProtocol.REPLY_JOB_STATE ||
                        queried.record?.let { CliJobIdentity.matches(it, original.jobId, original.requestSha256) } !=
                        true -> {
                        unavailable(queried)
                    }

                    queried.record.state.terminal -> {
                        reconcile(original)
                    }

                    else -> {
                        readProgress()
                        waitForNextPoll(pollIntervalMs)
                    }
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

    private fun reconcile(original: CliModelJobRecord): AwaitOutcome {
        val result = transact(CliRuntimeProtocol.TRANSACTION_JOB_FETCH_RESULT)
        return if (result.status == CliRuntimeProtocol.REPLY_JOB_STATE && result.record?.state?.terminal == true &&
            CliJobIdentity.matches(result.record, original.jobId, original.requestSha256)
        ) {
            AwaitOutcome.Terminal(result.record, result.events)
        } else {
            unavailable(result)
        }
    }

    private fun unavailable(result: CliModelWireResult) =
        AwaitOutcome.Unavailable(result.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
}
