package com.helix.app.proot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One observer and one control operation, so a blocked query cannot strand the user's stop controls. */
internal class BackgroundJobActions(
    private val scope: CoroutineScope,
    private val execute: (BackgroundJobUi, BackgroundJobAction, () -> Boolean) -> BackgroundJobActionOutcome,
    private val refresh: suspend () -> Unit,
) {
    private val lock = Any()
    private var query: Pending? = null
    private var control: Pending? = null
    private var latestRequest = 0L
    private val mutableState = MutableStateFlow<BackgroundJobActionUi?>(null)
    val state: StateFlow<BackgroundJobActionUi?> = mutableState

    private data class Pending(
        val id: Long,
    )

    private fun publish(
        pending: Pending,
        outcome: BackgroundJobActionOutcome,
    ) = synchronized(lock) {
        if (pending.id == latestRequest) mutableState.value = mutableState.value?.copy(outcome = outcome)
    }

    private fun complete(pending: Pending) =
        synchronized(lock) {
            if (query === pending) query = null
            if (control === pending) control = null
            mutableState.value =
                mutableState.value?.let {
                    it.copy(
                        busy = if (latestRequest == pending.id) false else it.busy,
                        queryBusy = query != null,
                        controlBusy = control != null,
                    )
                }
        }

    fun submit(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
    ) {
        val pending =
            synchronized(lock) {
                val observing = action == BackgroundJobAction.QUERY
                if ((if (observing) query else control) != null) return
                val next = Pending(++latestRequest)
                if (observing) query = next else control = next
                mutableState.value =
                    BackgroundJobActionUi(
                        job.callId,
                        action,
                        true,
                        queryBusy = query != null,
                        controlBusy = control != null,
                    )
                next
            }
        scope
            .launch {
                try {
                    val outcome = execute(job, action) { !isActive }
                    publish(pending, outcome)
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publish(pending, BackgroundJobActionOutcome.FAILED)
                }
            }.invokeOnCompletion {
                complete(pending)
            }
    }
}
