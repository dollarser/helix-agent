package com.helix.app.proot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** One bounded user operation at a time, independent of the currently visible session. */
internal class BackgroundJobActions(
    private val scope: CoroutineScope,
    private val execute: (BackgroundJobUi, BackgroundJobAction, () -> Boolean) -> BackgroundJobActionOutcome,
    private val refresh: suspend () -> Unit,
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<BackgroundJobActionUi?>(null)
    val state: StateFlow<BackgroundJobActionUi?> = mutableState

    fun submit(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
    ) {
        if (!gate.tryLock()) return
        mutableState.value = BackgroundJobActionUi(job.callId, action, true)
        scope
            .launch {
                try {
                    val outcome = execute(job, action) { !isActive }
                    mutableState.value = BackgroundJobActionUi(job.callId, action, false, outcome)
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableState.value =
                        BackgroundJobActionUi(
                            job.callId,
                            action,
                            false,
                            BackgroundJobActionOutcome.FAILED,
                        )
                }
            }.invokeOnCompletion {
                mutableState.value = mutableState.value?.copy(busy = false)
                gate.unlock()
            }
    }
}
