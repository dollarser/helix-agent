package com.helix.app.provider

import android.content.Context
import com.helix.core.model.ModelEvent
import com.helix.core.storage.HelixStorage
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal class SubscriptionResultRecovery(
    private val context: Context,
    private val storage: HelixStorage,
) {
    private val client = CliModelJobClient(CliRuntimeSupervisor(context))
    private val store = SubscriptionResultStore(storage, File(context.filesDir, "workspaces/app"))

    fun recover(
        turnId: String,
        modelCallId: String,
        localOnly: Boolean,
    ): SubscriptionRecoveredOutput? {
        check(storage.turns.resolve(turnId).state == "INTERRUPTED")
        val call = storage.modelCalls.resolve(modelCallId)
        check(call.turnId == turnId && call.state == "INTERRUPTED")
        val ownership = LocalModelCallContext(turnId, modelCallId)
        return if (localOnly) {
            visibleOutput(store.readLocal(ownership) ?: return null)
        } else {
            recoverAndConfirm(ownership)
        }
    }

    private fun recoverAndConfirm(ownership: LocalModelCallContext): SubscriptionRecoveredOutput {
        val job =
            SubscriptionJobBindingStore(storage)
                .resolve(ownership.modelCallId)
                .getValue("jobId")
                .jsonPrimitive.content
        val queried = client.query(job) as? CliModelJobClient.StateOutcome.Ok ?: error("Runtime unavailable")
        check(queried.record.state == CliModelJobState.SUCCEEDED)
        val events = store.read(ownership, queried.record) ?: fetchAndPersist(ownership, queried.record)
        val acknowledged = SubscriptionAcknowledgements.acknowledge(context, queried.record)
        return visibleOutput(events).copy(acknowledged = acknowledged)
    }

    private fun visibleOutput(events: List<ModelEvent>): SubscriptionRecoveredOutput {
        val text =
            events.joinToString("") {
                when (it) {
                    is ModelEvent.TextDelta -> it.text
                    is ModelEvent.Refusal -> it.safeReason.orEmpty()
                    else -> ""
                }
            }
        return SubscriptionRecoveredOutput.fromText(text)
    }

    private fun fetchAndPersist(
        ownership: LocalModelCallContext,
        expected: com.helix.runtime.cli.client.CliModelJobRecord,
    ): List<ModelEvent> {
        val fetched = client.fetchResult(expected.jobId) as CliModelJobClient.StateOutcome.Ok
        check(fetched.record.copy(reconciledAtEpochMillis = null) == expected.copy(reconciledAtEpochMillis = null))
        check(fetched.record.state == CliModelJobState.SUCCEEDED)
        val events = requireNotNull(fetched.events)
        store.persist(ownership, fetched.record, events)
        return events
    }
}
