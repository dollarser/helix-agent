package com.helix.runtime.proot.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An ephemeral preview, never the durable output archive or execution proof. */
data class JobLogPage(
    val cursor: String,
    val stream: Int,
    val bytes: ByteArray,
    val eof: Boolean,
    val truncated: Boolean,
)

/**
 * One worker drains a bounded queue independently of UI reads. Backpressure, quota and IO
 * failures stop the preview at a visible prefix; they never stop the process output pumps.
 * The process-local generation deliberately expires across Runtime death. Durable results
 * remain in the existing result store. Up to four 4 MiB spools = 16 MiB on disk.
 */
class JobLogSpool(
    private val root: File,
    private val executor: Executor =
        Executors.newSingleThreadExecutor {
            Thread(it, "proot-log").apply { isDaemon = true }
        },
    private val usableSpace: () -> Long = { root.usableSpace },
) {
    companion object {
        const val CHUNK_BYTES = 8192
        const val JOB_BYTES = 4 * 1024 * 1024
        const val MAX_JOBS = 4
        const val MAX_CHUNKS = 4096
    }

    private val jobs = linkedMapOf<String, Sink>()

    init {
        // Only this ephemeral log directory, never the job journal or result archives.
        check(!root.exists() || root.deleteRecursively()) { "Cannot clear expired log spool" }
        check(root.mkdirs()) { "Cannot create log spool" }
    }

    @Synchronized
    fun open(jobId: String): Sink? {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(jobId !in jobs)
        if (jobs.size == MAX_JOBS && !evictClosed()) return null
        return Sink(jobId, File(root, "$jobId.log")).also {
            jobs[jobId] = it
            executor.execute(it::writeLoop)
        }
    }

    @Synchronized
    fun read(
        jobId: String,
        cursor: String?,
    ): JobLogPage? = jobs[jobId]?.read(cursor)

    private fun evictClosed(): Boolean {
        val oldest = jobs.entries.firstOrNull { it.value.ended } ?: return false
        val removed = !oldest.value.file.exists() || oldest.value.file.delete()
        if (removed) jobs.remove(oldest.key)
        return removed
    }

    inner class Sink internal constructor(
        private val jobId: String,
        internal val file: File,
    ) {
        private val generation = UUID.randomUUID().toString()
        private val queue = ArrayBlockingQueue<Pair<Int, ByteArray>>(16)
        private val offsets = mutableListOf(0L)
        private val lock = Any()
        private var reserved = 0
        private var chunks = 0

        @Volatile private var closing = false

        @Volatile private var lost = false

        @Volatile var ended = false
            private set

        /** Called by output drain threads: bounded copies and non-blocking offer only. */
        @Synchronized
        fun offer(
            stream: Int,
            bytes: ByteArray,
            length: Int,
        ) {
            require(stream == 1 || stream == 2)
            require(length in 0..bytes.size)
            if (closing || lost) return
            var start = 0
            while (start < length) {
                val size = minOf(CHUNK_BYTES, length - start)
                if (chunks == MAX_CHUNKS || reserved + size + 5 > JOB_BYTES ||
                    !queue.offer(stream to bytes.copyOfRange(start, start + size))
                ) {
                    lost = true
                    return
                }
                reserved += size + 5
                chunks++
                start += size
            }
        }

        @Synchronized
        fun finish(truncated: Boolean = false) {
            lost = lost || truncated
            closing = true
        }

        internal fun writeLoop() {
            try {
                RandomAccessFile(file, "rw").use { output ->
                    drainQueue(output)
                }
            } catch (_: IOException) {
                lost = true
            } catch (_: InterruptedException) {
                lost = true
                Thread.currentThread().interrupt()
            } finally {
                finish(lost)
                queue.clear()
                ended = true
            }
        }

        private fun drainQueue(output: RandomAccessFile) {
            while (!closing || queue.isNotEmpty()) {
                val chunk = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (usableSpace() < CHUNK_BYTES * 4L) throw IOException("Log preview has insufficient storage")
                writeChunk(output, chunk)
            }
        }

        private fun writeChunk(
            output: RandomAccessFile,
            chunk: Pair<Int, ByteArray>,
        ) = synchronized(lock) {
            output.writeByte(chunk.first)
            output.writeInt(chunk.second.size)
            output.write(chunk.second)
            offsets.add(output.filePointer)
        }

        internal fun read(cursor: String?): JobLogPage =
            synchronized(lock) {
                val prefix = "$jobId:$generation:"
                val offset =
                    if (cursor == null) {
                        0L
                    } else {
                        require(cursor.startsWith(prefix)) { "Wrong job or expired log generation" }
                        cursor.removePrefix(prefix).toLongOrNull() ?: error("Invalid log cursor")
                    }
                val index = offsets.binarySearch(offset)
                require(index >= 0) { "Invalid log boundary" }
                if (index == offsets.lastIndex) {
                    return@synchronized JobLogPage("$prefix$offset", 1, byteArrayOf(), ended, lost)
                }
                try {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(offset)
                        val stream = input.readUnsignedByte()
                        val size = input.readInt()
                        require(stream in 1..2 && size in 1..CHUNK_BYTES)
                        val bytes = ByteArray(size)
                        input.readFully(bytes)
                        JobLogPage(
                            "$prefix${offsets[index + 1]}",
                            stream,
                            bytes,
                            ended && index + 1 == offsets.lastIndex,
                            lost,
                        )
                    }
                } catch (error: IOException) {
                    lost = true
                    throw error
                }
            }
    }
}
