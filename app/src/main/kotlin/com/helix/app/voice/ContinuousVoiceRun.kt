package com.helix.app.voice

import com.helix.feature.conversation.input.appendVoiceDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface ContinuousCaptureOwner {
    fun source(value: PcmCapture?)

    fun asr(value: StreamingAsr?)

    fun state(value: VoiceCapture.State)

    fun phase(value: VoiceCapture.Phase)

    fun interrupted()
}

internal class ContinuousCaptureFailure(
    val failure: VoiceCapture.Failure,
    cause: Throwable? = null,
) : IllegalStateException("CONTINUOUS_CAPTURE_${failure.name}", cause)

/** Bounded recorder/VAD producer and one ordered recognizer. Saturation stops; no silent audio loss. */
internal class ContinuousVoiceRun(
    private val factory: StreamingAsrFactory,
    private val source: (() -> Unit) -> PcmCapture,
    private val segmenter: () -> SpeechSegments,
    private val owner: ContinuousCaptureOwner,
) {
    private val stopping = AtomicBoolean(false)
    private val committed = AtomicReference("")
    private var session: StreamingAsr? = null

    suspend fun run(
        finish: AtomicBoolean,
        cancelled: AtomicBoolean,
    ): String =
        coroutineScope {
            val queue = Channel<FloatArray>(2)
            var consumer: Job? = null
            try {
                // Offline warming is reusable; a cloud socket is opened only for an actual completed utterance.
                if (!factory.uploadsAudio) openRecognizer()
                currentCoroutineContext().ensureActive()
                if (!cancelled.get()) {
                    consumer =
                        launch(Dispatchers.IO) {
                            for (samples in queue) {
                                if (cancelled.get()) break
                                recognize(samples, cancelled)
                            }
                        }
                    produce(queue, finish, cancelled)
                    stopping.set(true)
                    owner.phase(VoiceCapture.Phase.FINISHING)
                    queue.close()
                    consumer.join()
                }
                committed.get()
            } finally {
                queue.cancel()
                consumer?.cancel()
                (session as? InterruptibleAsr)?.interrupt()
                withContext(NonCancellable) {
                    consumer?.join()
                    closeRecognizer()
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // Opening failure must remain distinct from microphone failure.
    private suspend fun openRecognizer() {
        try {
            runInterruptible {
                factory.open().also {
                    session = it
                    owner.asr(it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ContinuousCaptureFailure(recognitionFailure, failure)
        }
    }

    private fun closeRecognizer() {
        try {
            session?.close()
        } finally {
            session = null
            owner.asr(null)
        }
    }

    // Preserve typed failures and cancellation; other recognizer failures become an explicit boundary failure.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun recognize(
        samples: FloatArray,
        cancelled: AtomicBoolean,
    ) {
        try {
            decode(samples, cancelled)
        } catch (failure: ContinuousCaptureFailure) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ContinuousCaptureFailure(recognitionFailure, failure)
        }
    }

    private suspend fun decode(
        samples: FloatArray,
        cancelled: AtomicBoolean,
    ) {
        currentCoroutineContext().ensureActive()
        if (session == null) openRecognizer()
        val asr = requireNotNull(session)
        var offset = 0
        while (offset < samples.size && !cancelled.get()) {
            currentCoroutineContext().ensureActive()
            val end = minOf(samples.size, offset + 1600)
            val partial = asr.accept(samples.copyOfRange(offset, end))
            currentCoroutineContext().ensureActive()
            if (!cancelled.get()) publish(appendVoiceDraft(committed.get(), partial))
            offset = end
        }
        if (!cancelled.get()) {
            val text = asr.finish().trim()
            currentCoroutineContext().ensureActive()
            if (!cancelled.get()) {
                val next = appendVoiceDraft(committed.get(), text)
                if (next.length > 20000) throw ContinuousCaptureFailure(VoiceCapture.Failure.LIMIT)
                committed.set(next)
                publish(next)
            }
            if (asr is ReusableStreamingAsr) asr.nextUtterance() else closeRecognizer()
        }
    }

    private val recognitionFailure get() =
        if (factory.uploadsAudio) VoiceCapture.Failure.CONNECTION else VoiceCapture.Failure.MODEL

    private fun publish(text: String) {
        if (text.length > 20000) throw ContinuousCaptureFailure(VoiceCapture.Failure.LIMIT)
        val phase = if (stopping.get()) VoiceCapture.Phase.FINISHING else VoiceCapture.Phase.LISTENING
        owner.state(VoiceCapture.State(phase, text, committedText = committed.get()))
    }

    // Native construction and recorder ownership always fail explicitly and release independently.
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private suspend fun produce(
        queue: Channel<FloatArray>,
        finish: AtomicBoolean,
        cancelled: AtomicBoolean,
    ) {
        val endpoint =
            try {
                segmenter()
            } catch (failure: RuntimeException) {
                throw ContinuousCaptureFailure(VoiceCapture.Failure.MODEL, failure)
            }
        endpoint.use { detector ->
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return
            val recorder = source { owner.interrupted() }
            owner.source(recorder)
            try {
                recorder.use {
                    try {
                        publish(committed.get())
                        readFrames(recorder, detector, queue, finish, cancelled)
                    } finally {
                        recorder.stop()
                    }
                }
            } finally {
                owner.source(null)
            }
        }
    }

    private suspend fun readFrames(
        recorder: PcmCapture,
        detector: SpeechSegments,
        queue: Channel<FloatArray>,
        finish: AtomicBoolean,
        cancelled: AtomicBoolean,
    ) {
        val pcm = ShortArray(512)
        val deadline = android.os.SystemClock.elapsedRealtime() + 120000
        var count = 0
        var segments = 0
        while (!cancelled.get() && !finish.get() && withinBudget(count, segments, deadline)) {
            currentCoroutineContext().ensureActive()
            val read = recorder.read(pcm)
            check(read in 1..pcm.size)
            val found = detector.accept(FloatArray(read) { pcm[it] / 32768f })
            segments += found.size
            if (segments > 32) throw ContinuousCaptureFailure(VoiceCapture.Failure.LIMIT)
            enqueue(queue, found)
            count += read
        }
        if (!cancelled.get()) {
            val final = detector.finish()
            if (segments + final.size > 32) throw ContinuousCaptureFailure(VoiceCapture.Failure.LIMIT)
            enqueue(queue, final)
        }
    }

    private fun withinBudget(
        count: Int,
        segments: Int,
        deadline: Long,
    ) = count < 16000 * 120 && segments < 32 && android.os.SystemClock.elapsedRealtime() < deadline

    private fun enqueue(
        queue: Channel<FloatArray>,
        segments: List<FloatArray>,
    ) {
        for (samples in segments) {
            if (samples.size !in 1..16000 * 12) throw ContinuousCaptureFailure(VoiceCapture.Failure.LIMIT)
            if (!queue.trySend(samples).isSuccess) throw ContinuousCaptureFailure(VoiceCapture.Failure.BACKPRESSURE)
        }
    }
}
