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

    private enum class Action { CHECK, DOWNLOAD, REMOVE }

    private val mutableInstalledBytes = MutableStateFlow(0L)
    val installedBytes = mutableInstalledBytes.asStateFlow()

    fun refresh() = update(Action.CHECK)

    fun download() = update(Action.DOWNLOAD)

    fun remove() = update(Action.REMOVE)

    @Suppress("TooGenericExceptionCaught") // Every I/O or verification failure is visible, never a successful install.
    private fun update(action: Action) {
        if (job?.isCompleted == false) return
        mutablePhase.value = if (action == Action.DOWNLOAD) Phase.DOWNLOADING else Phase.CHECKING
        job =
            scope.launch {
                try {
                    when (action) {
                        Action.DOWNLOAD -> files.download()
                        Action.REMOVE -> files.removeInstalled()
                        Action.CHECK -> Unit
                    }
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
        if (mutablePhase.value !in setOf(Phase.DOWNLOADING, Phase.CHECKING)) return
        job?.cancel()
        files.cancelDownload()
        // Retain the Job until cleanup finishes; another click cannot start overlapping downloads.
    }

    private suspend fun checkInstalled() {
        val ready = files.ready() != null
        mutableInstalledBytes.value = files.installedBytes()
        mutablePhase.value = if (ready) Phase.READY else Phase.MISSING
    }
}
