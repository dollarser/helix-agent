package com.helix.app.voice

import com.helix.core.model.ReadAloudText

/** Only the already user-visible assistant text projection may construct this frame. */
data class VisibleReplyFrame(
    val sessionId: String,
    val turnId: String,
    val text: String,
    val phase: Phase,
) {
    enum class Phase { STREAMING, FINALIZING, COMPLETED, WAITING, CANCELLED }

    val speechId get() = "live:$sessionId:$turnId"
}

interface ReplySpeechOutput {
    fun begin(id: String)

    fun append(text: String): Boolean

    fun finish()

    fun cancel()
}

/** Explicit opt-in for one visible answer. Updates alone never start or restart playback.
 * Commit complete sentences or bounded 64-character pieces; retain the unfinished tail.
 * A rewrite of already committed text cancels rather than replaying a contradictory answer.
 */
class LiveReplySpeech(
    private val output: ReplySpeechOutput,
) {
    private var key: Pair<String, String>? = null
    private var committed = ""
    val activeId get() = key?.let { "live:${it.first}:${it.second}" }

    fun start(frame: VisibleReplyFrame) {
        stop()
        if (frame.phase != VisibleReplyFrame.Phase.STREAMING) return
        key = frame.sessionId to frame.turnId
        committed = ""
        output.begin(frame.speechId)
        update(frame)
    }

    @Suppress("ReturnCount") // Each guard rejects a distinct identity, lifecycle or rewritten-text boundary.
    fun update(frame: VisibleReplyFrame) {
        if (key == null) return
        if (key != (frame.sessionId to frame.turnId) || frame.phase in
            setOf(
                VisibleReplyFrame.Phase.WAITING,
                VisibleReplyFrame.Phase.CANCELLED,
            )
        ) {
            stop()
            return
        }
        if (frame.phase == VisibleReplyFrame.Phase.FINALIZING) return
        if (frame.text.length > ReadAloudText.MAX_TEXT || !frame.text.startsWith(committed)) {
            stop()
            return
        }
        val end = if (frame.phase == VisibleReplyFrame.Phase.COMPLETED) frame.text.length else stableEnd(frame.text)
        val fresh = frame.text.substring(committed.length, end)
        if (fresh.isNotBlank()) {
            val chunks =
                try {
                    ReadAloudText.chunks(fresh)
                } catch (failure: IllegalArgumentException) {
                    if (failure.message == "EMPTY_TEXT") emptyList() else throw failure
                }
            for (chunk in chunks) {
                if (!output.append(chunk)) {
                    stop()
                    return
                }
            }
        }
        committed = frame.text.substring(0, end)
        if (frame.phase == VisibleReplyFrame.Phase.COMPLETED) {
            key = null
            output.finish()
        }
    }

    private fun stableEnd(text: String): Int {
        var end = committed.length
        for (index in committed.length until text.length) {
            if (sentenceEnd(text, index) || index - end >= 63) {
                // Never cut a surrogate pair in half.
                end = if (text[index].isHighSurrogate()) index else index + 1
            }
        }
        return safeMarkdownEnd(text, end)
    }

    private fun sentenceEnd(
        text: String,
        index: Int,
    ) = text[index] in "。！？；\n" ||
        (text[index] in ".!?;" && index + 1 < text.length && text[index + 1].isWhitespace())

    private fun safeMarkdownEnd(
        text: String,
        end: Int,
    ): Int {
        val bracket = text.lastIndexOf('[', end - 1)
        if (bracket >= committed.length && bracket < end) {
            val labelEnd = text.indexOf(']', bracket + 1)
            val targetEnd = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
            val incompleteLabel = labelEnd < 0 || labelEnd >= end
            val incompleteTarget = text.getOrNull(labelEnd + 1) == '(' && (targetEnd < 0 || targetEnd >= end)
            if (incompleteLabel || incompleteTarget) {
                return bracket
            }
        }
        return end
    }

    fun stop() {
        key = null
        committed = ""
        output.cancel()
    }

    fun detach(id: String) {
        if (activeId == id) key = null
    }
}

/** Replaceable continuous text input: an adapter may send text and receive PCM concurrently.
 * nextText blocks with backpressure and returns null only at input completion. Implementations
 * must unblock their network on interrupt. This is not a claim of model-internal dual streaming.
 */
interface ContinuousTextSpeechEngine : InterruptibleSpeechEngine {
    fun generateStream(
        nextText: () -> String?,
        keepGoing: () -> Boolean,
        consume: (FloatArray, Int) -> Unit,
    )

    override fun generate(
        text: String,
        keepGoing: () -> Boolean,
        consume: (FloatArray, Int) -> Unit,
    ) {
        var pending: String? = text
        generateStream({ pending.also { pending = null } }, keepGoing, consume)
    }
}
