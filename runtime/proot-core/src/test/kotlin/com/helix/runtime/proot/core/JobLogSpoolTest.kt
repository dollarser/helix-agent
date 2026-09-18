package com.helix.runtime.proot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class JobLogSpoolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workers = mutableListOf<Runnable>()

    private fun spool(space: Long = Long.MAX_VALUE) =
        JobLogSpool(
            temporary.newFolder(),
            Executor {
                workers.add(it)
            },
        ) { space }

    @Test fun repeatedCursorIsIdempotentAndStreamsKeepTheirIdentity() {
        val logs = spool()
        val sink = logs.open("job_1")!!
        sink.offer(1, "out".toByteArray(), 3)
        sink.offer(2, "err".toByteArray(), 3)
        sink.finish()
        workers.single().run()
        val first = logs.read("job_1", null)!!
        val repeated = logs.read("job_1", null)!!
        assertEquals(first.cursor, repeated.cursor)
        assertArrayEquals(first.bytes, repeated.bytes)
        assertEquals(1, first.stream)
        assertFalse(first.eof)
        val second = logs.read("job_1", first.cursor)!!
        assertEquals(2, second.stream)
        assertEquals("err", second.bytes.decodeToString())
        assertTrue(second.eof)
        assertFalse(second.truncated)
        assertEquals(0, logs.read("job_1", second.cursor)!!.bytes.size)
    }

    @Test fun queueOverflowNeverBlocksDrainAndMarksThePrefixTruncated() {
        val logs = spool()
        val sink = logs.open("job_1")!!
        repeat(1000) { sink.offer(1, ByteArray(8192), 8192) }
        sink.finish()
        workers.single().run()
        var cursor: String? = null
        var count = 0
        do {
            val page = logs.read("job_1", cursor)!!
            assertTrue(page.truncated)
            count += page.bytes.size
            cursor = page.cursor
        } while (!page.eof)
        assertEquals(16 * 8192, count)
    }

    @Test fun wrongJobGenerationAndOffsetAreRejected() {
        val logs = spool()
        logs.open("job_1")!!.finish()
        logs.open("job_2")!!.finish()
        workers.forEach { it.run() }
        val cursor = logs.read("job_1", null)!!.cursor
        assertThrows(IllegalArgumentException::class.java) { logs.read("job_2", cursor) }
        assertThrows(IllegalArgumentException::class.java) { logs.read("job_1", cursor + "1") }
        assertThrows(IllegalArgumentException::class.java) { logs.read("job_1", "job_1:stale:0") }
        assertNull(logs.read("unknown", null))
    }

    @Test fun lowStorageReturnsTruncatedEofInsteadOfSuccessOrBlocking() {
        val logs = spool(0)
        val sink = logs.open("job_1")!!
        sink.offer(1, "out".toByteArray(), 3)
        sink.finish()
        workers.single().run()
        val page = logs.read("job_1", null)!!
        assertTrue(page.truncated)
        assertTrue(page.eof)
        assertEquals(0, page.bytes.size)
    }

    @Test fun activeSpoolsAreNotEvictedAndClosedSpoolsExpire() {
        val logs = spool()
        val sinks = (1..4).map { logs.open("job_$it")!! }
        assertNull(logs.open("job_5"))
        sinks.first().finish()
        workers.first().run()
        val old = logs.read("job_1", null)!!.cursor
        assertNotNull(logs.open("job_5"))
        assertNull(logs.read("job_1", old))
        sinks.drop(1).forEach { it.finish() }
    }

    @Test fun uiTextDecodesSplitUtf8SeparatelyAndStaysBounded() {
        val text = JobLogText()
        val bytes = "中🙂文".toByteArray()
        for (byte in bytes) {
            text.append(JobLogPage("unused", 1, byteArrayOf(byte), false, false))
            text.append(JobLogPage("unused", 2, byteArrayOf('x'.code.toByte()), false, false))
        }
        assertEquals("中🙂文", text.stdout)
        assertEquals("x".repeat(bytes.size), text.stderr)
        repeat(40) { text.append(JobLogPage("unused", 1, ByteArray(8192) { 65 }, false, false)) }
        text.append(JobLogPage("unused", 2, byteArrayOf(), true, true))
        assertTrue(text.truncated)
        assertTrue((text.stdout.length + text.stderr.length) * 2 <= 256 * 1024)
    }

    @Test fun textCapacityNeverSplitsASurrogatePairOrResumesAfterTruncation() {
        val text = JobLogText()
        repeat(15) { text.append(JobLogPage("", 1, ByteArray(8192) { 65 }, false, false)) }
        text.append(JobLogPage("", 1, ByteArray(8191) { 65 }, false, false))
        text.append(JobLogPage("", 1, "🙂".toByteArray(), false, false))
        text.append(JobLogPage("", 2, "later".toByteArray(), true, false))
        assertEquals(131071, text.stdout.length)
        assertFalse(text.stdout.last().isSurrogate())
        assertEquals("", text.stderr)
        assertTrue(text.truncated)
    }

    @Test fun diskQuotaAndTinyChunkMetadataAreBoundedEvenWithAFastWriter() {
        verifyQuota(8192, 511)
        verifyQuota(1, JobLogSpool.MAX_CHUNKS)
    }

    private fun verifyQuota(
        size: Int,
        accepted: Int,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val root = temporary.newFolder()
        val logs = JobLogSpool(root, executor) { Long.MAX_VALUE }
        val sink = logs.open("quota")!!
        var cursor: String? = null
        try {
            repeat(accepted) {
                sink.offer(1, ByteArray(size), size)
                val deadline = System.nanoTime() + 5_000_000_000L
                var page: JobLogPage
                do {
                    assertTrue(System.nanoTime() < deadline)
                    page = logs.read("quota", cursor)!!
                    Thread.yield()
                } while (page.bytes.isEmpty())
                assertFalse(page.truncated)
                cursor = page.cursor
            }
            sink.offer(2, ByteArray(size), size)
            sink.finish()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!sink.ended) {
                assertTrue(System.nanoTime() < deadline)
                Thread.yield()
            }
            val end = logs.read("quota", cursor)!!
            assertTrue(end.eof && end.truncated)
            assertEquals(0, end.bytes.size)
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= JobLogSpool.JOB_BYTES)
        } finally {
            sink.finish()
            executor.shutdownNow()
        }
    }

    @Test fun newProcessGenerationRejectsOldCursorAndRemovesEphemeralFiles() {
        val root = temporary.newFolder()
        val first = JobLogSpool(root, Executor { workers.add(it) })
        first.open("same")!!.finish()
        workers.single().run()
        val old = first.read("same", null)!!.cursor
        val next = JobLogSpool(root, Executor { workers.add(it) })
        next.open("same")!!.finish()
        workers.last().run()
        assertThrows(IllegalArgumentException::class.java) { next.read("same", old) }
    }
}
