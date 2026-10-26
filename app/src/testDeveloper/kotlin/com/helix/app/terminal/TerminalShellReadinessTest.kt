package com.helix.app.terminal

import com.helix.runtime.proot.core.PtyOutputPage
import com.helix.runtime.proot.core.PtyProcessIdentity
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.ipc.PtySessionReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalShellReadinessTest {
    private val running =
        PtySessionRecord(
            PtySessionOrigin("new", "g", "new", "/workspace", "runtime", 1, 1, 1, 60_001),
        ).started(PtyProcessIdentity(123, 42))

    @Test fun newRunningShellRequiresActualPromptAndPreservesReadCursor() {
        val pages = ArrayDeque(listOf(page("hel", "1"), page("ix> ", "2")))
        val cursors = mutableListOf<String?>()
        val reply =
            awaitShellPrompt({ PtySessionReply(running) }, {
                cursors.add(it)
                pages.removeFirst()
            }, pause = {})
        assertEquals(running, reply.record)
        assertEquals(listOf(null, "1"), cursors)
    }

    @Test fun statusAloneAndLostOutputCannotPretendInteractiveReadiness() {
        val pages = ArrayDeque(listOf(page("hel", "1"), page("ix> ", "2", gap = true)))
        var time = 0L
        val failure =
            assertThrows(IllegalStateException::class.java) {
                awaitShellPrompt(
                    { PtySessionReply(running) },
                    { pages.removeFirst() },
                    clock = { time },
                    pause = { time += 8_000_000_000L },
                )
            }
        assertEquals("SHELL_START_TIMEOUT", failure.message)
    }

    @Test fun unknownStoppedAndMissingJournalNeverReportReady() {
        for (record in listOf(running.runtimeLost(), running.stoppedTree(0), null)) {
            assertThrows(IllegalStateException::class.java) {
                awaitShellPrompt({ PtySessionReply(record) }, { error("must not read after failed state") })
            }
        }
    }

    @Test fun eofBeforePromptFailsAndInterruptionPropagatesWithoutAnotherQuery() {
        assertThrows(IllegalStateException::class.java) {
            awaitShellPrompt({ PtySessionReply(running) }, { page("", "1", eof = true) })
        }
        var queries = 0
        assertThrows(InterruptedException::class.java) {
            awaitShellPrompt(
                {
                    queries++
                    PtySessionReply(running)
                },
                { page("", "1") },
                pause = { throw InterruptedException("cancelled") },
            )
        }
        assertEquals(1, queries)
    }

    private fun page(
        text: String,
        cursor: String,
        gap: Boolean = false,
        eof: Boolean = false,
    ) = PtyOutputPage(cursor, text.toByteArray(), gap, eof)
}
