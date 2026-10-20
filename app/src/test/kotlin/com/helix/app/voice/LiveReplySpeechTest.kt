package com.helix.app.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LiveReplySpeechTest {
    @Test fun incrementalCursorFeedsFakeTtsBeforeFinalAndStopPreventsMoreRequests() {
        val requests = mutableListOf<String>()
        val audio = mutableListOf<Float>()
        var engine: CloudSpeechEngine? = null
        val output =
            object : ReplySpeechOutput {
                override fun begin(id: String) {
                    engine =
                        CloudSpeechEngine(
                            object : CloudTtsConnection {
                                override fun stream(
                                    text: String,
                                    consume: (FloatArray, Int) -> Unit,
                                ) {
                                    requests.add(text)
                                    consume(floatArrayOf(0.5f), 16000)
                                }

                                override fun close() = Unit
                            },
                        )
                }

                override fun append(text: String): Boolean {
                    requireNotNull(engine).generate(text, { true }) { samples, _ -> audio.add(samples.single()) }
                    return true
                }

                override fun finish() {
                    engine?.close()
                }

                override fun cancel() {
                    engine?.interrupt()
                }
            }
        val speech = LiveReplySpeech(output)
        speech.start(frame("第一句。还没完成"))
        assertEquals(listOf("第一句。"), requests)
        assertEquals(listOf(0.5f), audio)
        speech.update(frame("第一句。还没完成"))
        assertEquals(1, requests.size)
        speech.stop()
        speech.update(frame("第一句。更多内容。", VisibleReplyFrame.Phase.COMPLETED))
        assertEquals(1, requests.size)
    }

    @Test fun incompleteMarkdownLinkDoesNotSpeakItsHiddenTarget() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("[网站](https://example.test/" + "path".repeat(30)))
        assertTrue(output.text.isEmpty())
        speech.update(
            frame(
                "[网站](https://example.test/" + "path".repeat(30) + ")。",
                VisibleReplyFrame.Phase.COMPLETED,
            ),
        )
        assertEquals(listOf("网站。"), output.text)
    }

    @Test fun decimalPointAndSurrogatePairAreNotPrematureCommitBoundaries() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("3.14"))
        assertTrue(output.text.isEmpty())
        speech.update(frame("3.14。"))
        assertEquals(listOf("3.14。"), output.text)
        speech.stop()
        val long = "a".repeat(63) + "😀" + " tail"
        speech.start(frame(long))
        speech.update(frame(long, VisibleReplyFrame.Phase.COMPLETED))
        assertTrue(output.text.none { it.lastOrNull()?.isHighSurrogate() == true })
    }

    private class Output : ReplySpeechOutput {
        val text = mutableListOf<String>()
        var starts = 0
        var finishes = 0
        var cancels = 0

        override fun begin(id: String) {
            starts++
        }

        override fun append(text: String): Boolean {
            this.text.add(text)
            return true
        }

        override fun finish() {
            finishes++
        }

        override fun cancel() {
            cancels++
        }
    }

    private fun frame(
        text: String,
        phase: VisibleReplyFrame.Phase = VisibleReplyFrame.Phase.STREAMING,
        session: String = "session",
        turn: String = "turn",
    ) = VisibleReplyFrame(session, turn, text, phase)

    @Test fun emitsBeforeCompletionAndDeduplicatesCumulativeSnapshots() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("第一句。未完成"))
        assertEquals(listOf("第一句。"), output.text)
        speech.update(frame("第一句。未完成"))
        speech.update(frame("第一句。第二句。尾部"))
        assertEquals(listOf("第一句。", "第二句。"), output.text)
        speech.update(frame("第一句。第二句。尾部", VisibleReplyFrame.Phase.COMPLETED))
        speech.update(frame("第一句。第二句。尾部", VisibleReplyFrame.Phase.COMPLETED))
        assertEquals(listOf("第一句。", "第二句。", "尾部"), output.text)
        assertEquals(1, output.finishes)
    }

    @Test fun finalMayReplaceOnlyTheUncommittedTail() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("已提交。待改写"))
        speech.update(frame("已提交。修订尾部", VisibleReplyFrame.Phase.COMPLETED))
        assertEquals(listOf("已提交。", "修订尾部"), output.text)
        assertEquals(1, output.finishes)
    }

    @Test fun rewriteOfSpokenPrefixCancelsWithoutReplay() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("旧文字。"))
        speech.update(frame("完全改写。", VisibleReplyFrame.Phase.COMPLETED))
        assertEquals(listOf("旧文字。"), output.text)
        assertEquals(null, speech.activeId)
        assertEquals(0, output.finishes)
    }

    @Test fun explicitStopIsStickyAcrossTokensAndFinalReplacement() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("第一句。"))
        speech.stop()
        speech.update(frame("第一句。迟到新句。"))
        speech.update(frame("第一句。迟到新句。", VisibleReplyFrame.Phase.COMPLETED))
        assertEquals(1, output.starts)
        assertEquals(listOf("第一句。"), output.text)
    }

    @Test fun sessionOrTurnChangeCancelsAndDoesNotArmAnotherAnswer() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("旧会话。"))
        speech.update(frame("新会话。", session = "next"))
        speech.update(frame("旧会话。迟到。"))
        assertEquals(null, speech.activeId)
        assertEquals(listOf("旧会话。"), output.text)
        speech.start(frame("新任务。", turn = "next-turn"))
        speech.update(frame("另一个任务。", turn = "other-turn"))
        assertEquals(listOf("旧会话。", "新任务。"), output.text)
    }

    @Test fun toolWaitDiscardsTailAndRequiresExplicitRestart() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("可见说明。尚未完成"))
        speech.update(frame("不应朗读的工具内容", VisibleReplyFrame.Phase.WAITING))
        speech.update(frame("工具之后的可见回答。"))
        assertEquals(listOf("可见说明。"), output.text)
        speech.start(frame("工具之后的可见回答。"))
        assertEquals(listOf("可见说明。", "工具之后的可见回答。"), output.text)
    }

    @Test fun cancelledTurnAndOversizedTextNeverResume() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("未提交尾部"))
        speech.update(frame("", VisibleReplyFrame.Phase.CANCELLED))
        speech.update(frame("迟到文字。"))
        assertTrue(output.text.isEmpty())
        speech.start(frame("a".repeat(20001)))
        assertEquals(null, speech.activeId)
        assertTrue(output.text.isEmpty())
    }

    @Test fun waitsForPersistedFinalWithoutTreatingMissingTextAsReplacement() {
        val output = Output()
        val speech = LiveReplySpeech(output)
        speech.start(frame("已提交。尾部"))
        speech.update(frame("", VisibleReplyFrame.Phase.FINALIZING))
        assertEquals(frame("").speechId, speech.activeId)
        speech.update(frame("已提交。最终尾部", VisibleReplyFrame.Phase.COMPLETED))
        assertEquals(listOf("已提交。", "最终尾部"), output.text)
    }

    @Test fun continuousEngineReceivesTextBeforeInputCompletion() {
        val input = Channel<String>(Channel.UNLIMITED)
        val first = CountDownLatch(1)
        val received = mutableListOf<String>()
        var streamCalls = 0
        val failure = AtomicReference<Throwable?>()
        val engine =
            object : ContinuousTextSpeechEngine {
                override fun generateStream(
                    nextText: () -> String?,
                    keepGoing: () -> Boolean,
                    consume: (FloatArray, Int) -> Unit,
                ) {
                    streamCalls++
                    while (keepGoing()) {
                        val text = nextText() ?: break
                        received.add(text)
                        consume(floatArrayOf(0f), 16000)
                        first.countDown()
                    }
                }

                override fun interrupt() {
                    input.cancel()
                }

                override fun close() = Unit
            }
        val worker =
            thread {
                try {
                    runBlocking { generateSpeechStream(engine, input, { true }) { _, _ -> } }
                } catch (
                    error: Throwable,
                ) {
                    failure.set(error)
                }
            }
        try {
            input.trySend("第一段").getOrThrow()
            assertTrue(first.await(2, TimeUnit.SECONDS))
            assertTrue(worker.isAlive)
            input.trySend("第二段").getOrThrow()
            input.close()
            worker.join(2000)
            assertTrue(!worker.isAlive)
            assertEquals(null, failure.get())
            assertEquals(listOf("第一段", "第二段"), received)
            assertEquals(1, streamCalls)
        } finally {
            input.cancel()
            worker.join(2000)
        }
    }
}
