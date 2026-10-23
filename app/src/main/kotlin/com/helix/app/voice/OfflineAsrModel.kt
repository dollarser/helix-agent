package com.helix.app.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One app-owned download. Readiness never records audio or initiates network access. */
class OfflineAsrModel(
    val files: LocalSpeechModel,
) {
    enum class Phase { CHECKING, MISSING, DOWNLOADING, READY, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val mutablePhase = MutableStateFlow(Phase.CHECKING)
    val phase = mutablePhase.asStateFlow()
    val downloadedBytes get() = files.downloadedBytes
    val totalBytes get() = files.totalBytes

    fun refresh() = update(false)

    fun download() = update(true)

    @Suppress("TooGenericExceptionCaught") // Every I/O or verification failure is visible, never a successful install.
    private fun update(transfer: Boolean) {
        if (job?.isCompleted == false) return
        mutablePhase.value = if (transfer) Phase.DOWNLOADING else Phase.CHECKING
        job =
            scope.launch {
                try {
                    if (transfer) files.download()
                    checkInstalled()
                } catch (cancelled: CancellationException) {
                    try {
                        withContext(NonCancellable) { checkInstalled() }
                    } catch (_: Exception) {
                        mutablePhase.value = Phase.FAILED
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    if (currentCoroutineContext().isActive) {
                        mutablePhase.value = Phase.FAILED
                    } else {
                        withContext(NonCancellable) { checkInstalled() }
                        throw CancellationException("ASR download cancelled", failure)
                    }
                }
            }
    }

    fun cancel() {
        if (mutablePhase.value != Phase.DOWNLOADING) return
        job?.cancel()
        files.cancelDownload()
        // Retain the Job until cleanup finishes; another click cannot start overlapping downloads.
    }

    private suspend fun checkInstalled() {
        mutablePhase.value = if (files.ready() == null) Phase.MISSING else Phase.READY
    }
}
