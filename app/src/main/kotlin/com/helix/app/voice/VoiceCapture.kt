package com.helix.app.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One foreground, user-started capture. Native inference and recording stay off the UI thread. */
class VoiceCapture(
    context: Context,
    private val scope: CoroutineScope,
    private val factory: StreamingAsrFactory = OfflineAsrFactory(context.assets),
    private val source: (() -> Unit) -> PcmCapture = { interrupted -> MicrophoneCapture(context, interrupted) },
) {
    enum class Phase { IDLE, LOADING, LISTENING, FINISHING, REVIEW, CANCELLED, FAILED }

    enum class Failure { MODEL, AUDIO, FOCUS, INTERRUPTED, CONNECTION }

    val uploadsAudio get() = factory.uploadsAudio
    val destination get() = factory.destination
    val supportsPartial get() = factory.supportsPartial

    data class State(
        val phase: Phase = Phase.IDLE,
        val text: String = "",
        val failure: Failure? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val finish = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val stateLock = Any()

    private fun publish(state: State) =
        synchronized(stateLock) {
            if (!cancelled.get()) mutableState.value = state
        }

    private var job: Job? = null
    private val activeSource = AtomicReference<PcmCapture?>(null)
    private val activeAsr = AtomicReference<StreamingAsr?>(null)

    // One bounded capture with ordered native cleanup.
    @Suppress("LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod", "ComplexCondition")
    fun start() {
        if (job?.isCompleted == false) return
        finish.set(false)
        cancelled.set(false)
        mutableState.value = State(Phase.LOADING)
        job =
            scope.launch(Dispatchers.IO) {
                var asr: StreamingAsr? = null
                var recorder: PcmCapture? = null
                var failure = if (uploadsAudio) Failure.CONNECTION else Failure.MODEL
                try {
                    asr = factory.open()
                    activeAsr.set(asr)
                    if (cancelled.get() || !currentCoroutineContext().isActive) return@launch
                    failure = Failure.AUDIO
                    recorder = source { cancel(Failure.INTERRUPTED) }
                    activeSource.set(recorder)
                    if (cancelled.get() || !currentCoroutineContext().isActive) return@launch
                    publish(State(Phase.LISTENING))
                    if (uploadsAudio) failure = Failure.CONNECTION
                    val samples = ShortArray(1600)
                    var count = 0
                    val deadline = android.os.SystemClock.elapsedRealtime() + 120000
                    while (!cancelled.get() && !finish.get() &&
                        currentCoroutineContext().isActive && count < 16000 * 120 &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                    ) {
                        val read = recorder.read(samples)
                        check(read in 1..samples.size)
                        val text = asr.accept(FloatArray(read) { samples[it] / 32768f })
                        publish(State(Phase.LISTENING, text))
                        count += read
                    }
                    recorder.stop()
                    if (!cancelled.get() && currentCoroutineContext().isActive) {
                        publish(mutableState.value.copy(phase = Phase.FINISHING))
                        val text = asr.finish()
                        publish(State(Phase.REVIEW, text))
                    }
                } catch (interrupted: kotlinx.coroutines.CancellationException) {
                    throw interrupted
                } catch (_: AudioFocusUnavailableException) {
                    publish(State(Phase.FAILED, failure = Failure.FOCUS))
                } catch (_: Exception) {
                    publish(mutableState.value.copy(phase = Phase.FAILED, failure = failure))
                } catch (_: LinkageError) {
                    publish(State(Phase.FAILED, failure = Failure.MODEL))
                } finally {
                    activeAsr.set(null)
                    activeSource.set(null)
                    try {
                        recorder?.close()
                    } finally {
                        asr?.close()
                    }
                }
            }
    }

    fun finish() {
        finish.set(true)
    }

    fun cancel(reason: Failure? = null) {
        synchronized(stateLock) {
            cancelled.set(true)
            mutableState.value =
                mutableState.value.copy(
                    phase = if (reason == null) Phase.CANCELLED else Phase.FAILED,
                    failure = reason,
                )
        }
        try {
            activeSource.get()?.stop()
        } catch (_: IllegalStateException) {
            // The worker still owns release for an already stopped or failed recorder.
        }
        (activeAsr.get() as? InterruptibleAsr)?.interrupt()
    }
}
