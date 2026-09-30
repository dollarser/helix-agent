package com.helix.app.provider

import android.content.Context
import com.helix.app.chat.AutomaticRuntimeCollection
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliResultAckQueue
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Receipt delivery has app lifetime; it is independent of Provider creation and capability probing. */
internal fun startSubscriptionAcknowledgementRecovery(
    context: Context,
    scope: CoroutineScope,
) = SubscriptionAcknowledgements.start(context, scope)

/** App-lifetime receipt delivery, independent of whether the result happens to be visible in chat. */
internal object SubscriptionAcknowledgements {
    @Volatile private var observe: (() -> Unit)? = null

    fun start(
        context: Context,
        scope: CoroutineScope,
    ) {
        val app = context.applicationContext
        val queue = queue(app)
        val client = CliModelJobClient(CliRuntimeSupervisor(app))
        val io = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
        val observer =
            AutomaticRuntimeCollection(io, onPaused = {
                android.util.Log.w("SubscriptionAck", "Acknowledgement paused; durable receipt retained")
            })
        val gate = Mutex()
        observe = {
            io.launch {
                runCatching { queue.pending() }
                    .onFailure { android.util.Log.w("SubscriptionAck", "Receipt store unavailable") }
                    .getOrDefault(emptyList())
                    .forEach { id ->
                        observer.request(id) {
                            gate.withLock {
                                if (queue.attempt(id, client::acknowledgeResult)) {
                                    // No permanent memo: a concurrent duplicate may enqueue this identity again.
                                    AutomaticRuntimeCollection.Observation.DEFERRED
                                } else {
                                    AutomaticRuntimeCollection.Observation.RETRY
                                }
                            }
                        }
                    }
            }
            Unit
        }
        observe?.invoke()
    }

    /** Persisted output (or an explicit discard) is a caller precondition; an ACK never regenerates output. */
    fun acknowledge(
        context: Context,
        record: CliModelJobRecord,
    ): Boolean {
        if (record.reconciledAtEpochMillis != null) return true
        val queue = queue(context)
        queue.enqueue(record)
        val interrupted = Thread.interrupted()
        val confirmed =
            try {
                val client = CliModelJobClient(CliRuntimeSupervisor(context.applicationContext))
                runCatching { queue.attempt(record.jobId, client::acknowledgeResult) }
                    .onFailure { android.util.Log.w("SubscriptionAck", "Receipt remains pending") }
                    .getOrDefault(false)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        observe?.invoke()
        return confirmed
    }

    private fun queue(context: Context) = CliResultAckQueue(File(context.filesDir, "subscription-result-acks"))
}
