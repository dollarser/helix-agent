package com.helix.app.voice

import com.helix.core.model.ReadAloudText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val busy: Boolean = false,
)

/** One physical synthesis/output owner. Cancel signals do not release capacity before native exit. */
@Suppress("TooManyFunctions") // One physical owner for playback, download and confirmed resource removal.
class ReadAloudService(
    val model: LocalSpeechModel,
    private val engine: (File) -> SpeechEngine,
    private val focus: (() -> Unit) -> AutoCloseable?,
    private val sink: (Int) -> SpeechSink,
    /** Explicit service-specific adapter; null retains local synthesis. Never an automatic fallback. */
    private val cloudEngine: (() -> InterruptibleSpeechEngine)? = null,
    val uploadDestination: String? = null,
    val segmentedHttp: Boolean = false,
    val accountIdentity: String? = null,
    private val synthesis: Mutex = Mutex(),
) {
    val uploadsText get() = cloudEngine != null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val epoch = AtomicLong()
    private val output = AtomicReference<SpeechSink?>(null)
    private val activeCloud = AtomicReference<InterruptibleSpeechEngine?>(null)
    private var job: Job? = null
    private var downloadJob: Job? = null
    val state = MutableStateFlow(ReadAloudState())
    private var liveInput: Channel<String>? = null
    private var livePlayback: Pair<String, String>? = null

    val livePlaybackTurnId: String?
        get() = livePlayback?.takeIf { state.value.messageId == it.first }?.second

    fun startLiveReply(frame: VisibleReplyFrame) {
        liveReply.start(frame)
        if (liveReply.activeId != null) livePlayback = frame.speechId to frame.turnId
    }

    val liveReply =
        LiveReplySpeech(
            object : ReplySpeechOutput {
                override fun begin(id: String) {
                    val input = Channel<String>(Channel.UNLIMITED)
                    liveInput = input
                    startPlayback(id) { input }
                }

                override fun append(text: String) = liveInput?.trySend(text)?.isSuccess == true

                override fun finish() {
                    liveInput?.close()
                }

                override fun cancel() {
                    liveInput?.cancel()
                    liveInput = null
                    stopPlayback()
                }
            },
        )

    fun play(
        id: String,
        text: String,
    ) {
        if (downloadJob?.isCompleted == false) return
        stop()
        startPlayback(id) {
            Channel<String>(Channel.UNLIMITED).also { input ->
                ReadAloudText.chunks(text).forEach { input.trySend(it).getOrThrow() }
                input.close()
            }
        }
    }

    // Failure is user-visible; never report synthesis/output errors as completion.
    @Suppress("TooGenericExceptionCaught")
    private fun startPlayback(
        id: String,
        input: () -> ReceiveChannel<String>,
    ) {
        val ticket = epoch.incrementAndGet()
        state.value = ReadAloudState(id, ReadAloudPhase.PREPARING)
        job =
            scope.launch {
                synthesis.withLock {
                    ensureActive()
                    var lease: AutoCloseable? = null
                    var chunks: ReceiveChannel<String>? = null
                    try {
                        chunks = withContext(Dispatchers.IO) { input() }
                        val directory = if (cloudEngine == null) model.ready() else null
                        if (directory == null && cloudEngine == null) {
                            state.value = ReadAloudState(id, ReadAloudPhase.MISSING)
                        } else {
                            ensureActive()
                            lease = requireNotNull(focus { if (epoch.get() == ticket) stop() }) { "AUDIO_FOCUS_DENIED" }
                            withContext(Dispatchers.IO) {
                                speak(requireNotNull(chunks), directory, ticket, id)
                            }
                            if (epoch.get() == ticket) state.value = ReadAloudState()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: LinkageError) {
                        if (epoch.get() == ticket) state.value = ReadAloudState(id, ReadAloudPhase.UNAVAILABLE)
                    } catch (failure: Exception) {
                        if (epoch.get() ==
                            ticket
                        ) {
                            state.value =
                                ReadAloudState(
                                    id,
                                    ReadAloudPhase.FAILED,
                                    (failure as? SpeechSocketFailure)?.closeCode == 1013,
                                )
                        }
                    } finally {
                        chunks?.cancel()
                        if (epoch.get() == ticket) liveReply.detach(id)
                        release(lease, ticket, id)
                    }
                }
            }
    }

    private data class AudioPart(
        val samples: FloatArray,
        val rate: Int,
    )

    /** One queued PCM part: synthesize ahead while output blocks, never accumulate a reply in RAM. */
    private suspend fun speak(
        chunks: ReceiveChannel<String>,
        directory: File?,
        ticket: Long,
        id: String,
    ) = coroutineScope {
        val audio = Channel<AudioPart>(capacity = 1)
        val producer =
            launch(Dispatchers.IO) {
                val active = currentCoroutineContext()
                try {
                    val voiceEngine = cloudEngine?.invoke() ?: engine(requireNotNull(directory))
                    if (voiceEngine is InterruptibleSpeechEngine) activeCloud.set(voiceEngine)
                    voiceEngine.use { voice ->
                        ensureActive()
                        val keepGoing = { active.isActive && epoch.get() == ticket }
                        generateSpeechStream(voice, chunks, keepGoing) { samples, rate ->
                            if (epoch.get() == ticket) runBlocking { audio.send(AudioPart(samples, rate)) }
                        }
                    }
                } finally {
                    activeCloud.set(null)
                    audio.close()
                }
            }
        try {
            val active = currentCoroutineContext()
            var outputRate: Int? = null
            for (part in audio) {
                ensureActive()
                if (epoch.get() != ticket) break
                check(outputRate == null || outputRate == part.rate) { "AUDIO_RATE_CHANGED" }
                outputRate = part.rate
                val target = output.get() ?: sink(part.rate).also(output::set)
                state.value = ReadAloudState(id, ReadAloudPhase.SPEAKING)
                target.write(part.samples) { active.isActive && epoch.get() == ticket }
            }
            ensureActive()
            output.get()?.finish { active.isActive && epoch.get() == ticket }
        } finally {
            audio.cancel()
            chunks.cancel()
            producer.cancel()
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
        liveReply.stop()
    }

    private fun stopPlayback() {
        livePlayback = null
        val ticket = epoch.incrementAndGet()
        val previous = job
        output.get()?.interrupt()
        previous?.cancel()
        activeCloud.get()?.interrupt()
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
                    } catch (failure: Exception) {
                        if (!currentCoroutineContext().isActive) {
                            state.value = ReadAloudState()
                            throw CancellationException("Speech model download cancelled", failure)
                        }
                        state.value = ReadAloudState(phase = ReadAloudPhase.FAILED)
                    }
                }
            }
    }

    fun leave() {
        stop()
        downloadJob?.cancel()
        model.cancelDownload()
    }

    /** Stop/await the existing physical owner before removing only its downloaded model parts. */
    suspend fun removeDownloadedModel() {
        leave()
        downloadJob?.join()
        synthesis.withLock {
            model.removeInstalled()
            state.value = ReadAloudState()
        }
    }

    fun close() {
        leave()
        scope.cancel()
    }
}
