package com.helix.app.provider

import android.content.Context
import com.helix.app.privacy.ProviderEvidenceCleanup
import com.helix.app.privacy.ProviderEvidenceCleanup.Status
import com.helix.core.storage.HelixStorage
import com.helix.runtime.cli.client.CliReplayMaintenanceClient
import com.helix.runtime.cli.client.CliRuntimeSupervisor

/** One bounded user-action page. Failure never undoes or disguises a completed session deletion. */
internal class SubscriptionReplayCleanup(
    context: Context,
    storage: HelixStorage,
) {
    private val client = CliReplayMaintenanceClient(CliRuntimeSupervisor(context.applicationContext))
    private val retention = SubscriptionReplayRetention(storage)

    // Report which independent phase failed, without private details.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun clean(after: String?): ProviderEvidenceCleanup {
        val page =
            try {
                client.page(after)
            } catch (_: Exception) {
                return ProviderEvidenceCleanup(Status.UNAVAILABLE, nextAfter = after)
            }
        val summary =
            ProviderEvidenceCleanup(
                status = if (page.nextAfter == null) Status.COMPLETE else Status.MORE_AVAILABLE,
                inspected = page.entries.size,
                inspectedBytes = page.entries.sumOf { it.bytes },
                retained = page.entries.size,
                nextAfter = page.nextAfter,
            )
        val candidates =
            try {
                retention.unreferenced(page.entries)
            } catch (_: Exception) {
                return summary.copy(status = Status.HISTORY_UNVERIFIED, nextAfter = after)
            }
        if (candidates.isEmpty()) return summary
        val result =
            try {
                client.prune(candidates)
            } catch (_: Exception) {
                // A lost reply is not evidence of no deletion. Retry starts with a fresh inventory.
                return summary.copy(status = Status.OUTCOME_UNKNOWN, retained = 0, nextAfter = after)
            }
        return summary.copy(
            status =
                when {
                    result.busy -> Status.BUSY
                    result.failed > 0 -> Status.PARTIAL_FAILURE
                    else -> summary.status
                },
            deleted = result.deleted,
            deletedBytes = result.deletedBytes,
            retained = page.entries.size - candidates.size + result.retained,
            failed = result.failed,
            nextAfter = if (result.busy || result.failed > 0) after else page.nextAfter,
        )
    }
}
