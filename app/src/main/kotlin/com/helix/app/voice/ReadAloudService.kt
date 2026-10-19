package com.helix.app.voice

import com.helix.core.model.ReadAloudText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class ReadAloudPhase { IDLE, PREPARING, SPEAKING, STOPPING, MISSING, FAILED, UNAVAILABLE, DOWNLOADING, READY }

data class ReadAloudState(
    val messageId: String? = null,
    val phase: ReadAloudPhase = ReadAloudPhase.IDLE,
)

/** One physical synthesis/output owner. Cancel signals do not release capacity before native exit. */
class ReadAloudService(
    val model: LocalVoiceModel,
    private val engine: (File) -> SpeechEngine,
    private val focus: (() -> Unit) -> AutoCloseable?,
    private val sink: (Int) -> SpeechSink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val epoch = AtomicLong()
    private val synthesis = Mutex()
    private val output = AtomicReference<SpeechSink?>(null)
    private var job: Job? = null
    private var downloadJob: Job? = null
    val state = MutableStateFlow(ReadAloudState())

    @Suppress("TooGenericExceptionCaught") // Failure is user-visible; do not turn native/output errors into completion.
    fun play(
        id: String,
        text: String,
    ) {
        if (downloadJob?.isCompleted == false) return
        stop()
        val ticket = epoch.incrementAndGet()
        state.value = ReadAloudState(id, ReadAloudPhase.PREPARING)
        job =
            scope.launch {
                synthesis.withLock {
                    ensureActive()
                    var lease: AutoCloseable? = null
                    try {
                        val chunks = withContext(Dispatchers.IO) { ReadAloudText.chunks(text) }
                        val directory = model.ready()
                        if (directory == null) {
                            state.value = ReadAloudState(id, ReadAloudPhase.MISSING)
                        } else {
                            ensureActive()
                            lease = requireNotNull(focus { if (epoch.get() == ticket) stop() }) { "AUDIO_FOCUS_DENIED" }
                            withContext(Dispatchers.IO) {
                                engine(directory).use { voice ->
                                    chunks.forEach { chunk ->
                                        ensureActive()
                                        voice.generate(chunk, { epoch.get() == ticket }) { samples, rate ->
                                            if (epoch.get() == ticket) {
                                                val target = output.get() ?: sink(rate).also(output::set)
                                                state.value = ReadAloudState(id, ReadAloudPhase.SPEAKING)
                                                target.write(samples) { epoch.get() == ticket }
                                            }
                                        }
                                    }
                                    output.get()?.finish { epoch.get() == ticket }
                                }
                            }
                            if (epoch.get() == ticket) state.value = ReadAloudState()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: LinkageError) {
                        if (epoch.get() == ticket) state.value = ReadAloudState(id, ReadAloudPhase.UNAVAILABLE)
                    } catch (_: Exception) {
                        if (epoch.get() == ticket) state.value = ReadAloudState(id, ReadAloudPhase.FAILED)
                    } finally {
                        release(lease, ticket, id)
                    }
                }
            }
    }

    @Suppress("TooGenericExceptionCaught") // Focus is released even when output cleanup fails.
    private suspend fun release(lease: AutoCloseable?, ticket: Long, id: String) =
        withContext(NonCancellable) {
            try {
                try {
                    withContext(Dispatchers.IO) { output.getAndSet(null)?.close() }
                } finally {
                    lease?.close()
                }
            } catch (_: Exception) {
                if (epoch.get() == ticket) state.value = ReadAloudState(id, ReadAloudPhase.FAILED)
            }
        }

    fun stop() {
        val ticket = epoch.incrementAndGet()
        val previous = job
        output.get()?.interrupt()
        previous?.cancel()
        if (previous?.isCompleted == false) {
            state.value = state.value.copy(phase = ReadAloudPhase.STOPPING)
            scope.launch {
                synthesis.withLock {
                    if (epoch.get() == ticket) state.value = ReadAloudState()
                }
            }
        } else if (state.value.phase !in setOf(ReadAloudPhase.DOWNLOADING, ReadAloudPhase.READY)) {
            state.value = ReadAloudState()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Incomplete downloads remain retryable; never fall back to cloud synthesis.
    fun download() {
        if (downloadJob?.isCompleted == false) return
        stop()
        downloadJob =
            scope.launch {
                synthesis.withLock {
                    ensureActive()
                    state.value = ReadAloudState(phase = ReadAloudPhase.DOWNLOADING)
                    try {
                        model.download()
                        state.value = ReadAloudState(phase = ReadAloudPhase.READY)
                    } catch (cancel: CancellationException) {
                        state.value = ReadAloudState()
                        throw cancel
                    } catch (_: Exception) {
                        state.value = ReadAloudState(phase = ReadAloudPhase.FAILED)
                    }
                }
            }
    }

    fun leave() {
        stop()
        model.cancelDownload()
        downloadJob?.cancel()
    }

    fun close() {
        leave()
        scope.cancel()
    }
}
