package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelProgressCodec
import java.io.File
import java.io.RandomAccessFile

/** Accessed under the owning runner's lock; never used as a durable completion record. */
internal class CodexJobProgress(
    private val file: File,
) {
    private var count = 0
    private var cursor = 0
    private var position = 0L
    private var sealed = false

    fun clear() {
        check(!file.exists() || file.delete()) { "preview cleanup failed" }
        count = 0
        cursor = 0
        position = 0L
        sealed = false
    }

    fun append(chunk: List<ModelEvent>) {
        if (sealed) return
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        RandomAccessFile(file, "rw").use { output ->
            output.seek(output.length())
            for (event in chunk) {
                val bytes = previewBytes(event)
                if (bytes == null) {
                    sealed = true
                    break
                }
                output.writeInt(bytes.size)
                output.write(bytes)
                count = Math.addExact(count, 1)
            }
        }
    }

    private fun previewBytes(event: ModelEvent): ByteArray? {
        if (event is ModelEvent.Completed || event is ModelEvent.Refusal || event is ModelEvent.Error) return null
        // A large event remains in the durable result; preview never skips ahead in the prefix.
        return try {
            CliModelProgressCodec.encode(listOf(event))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun read(offset: Int): List<ModelEvent> {
        require(offset in 0..count)
        if (offset == count) return emptyList()
        val result = ArrayList<ModelEvent>()
        RandomAccessFile(file, "r").use { input ->
            if (offset < cursor) {
                cursor = 0
                position = 0L
            }
            input.seek(position)
            while (cursor < offset) {
                val length = input.readInt()
                require(length in 1..CliModelProgressCodec.MAX_BATCH_BYTES)
                input.seek(input.filePointer + length)
                cursor++
            }
            var byteCount = ENVELOPE_RESERVE
            while (cursor < count && result.size < MAX_BATCH_EVENTS) {
                val start = input.filePointer
                val length = input.readInt()
                require(length in 1..CliModelProgressCodec.MAX_BATCH_BYTES)
                if (result.isNotEmpty() && byteCount + length > CliModelProgressCodec.MAX_BATCH_BYTES) {
                    input.seek(start)
                    break
                }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                result += CliModelProgressCodec.decode(bytes)
                byteCount += length
                cursor++
            }
            position = input.filePointer
        }
        return result
    }

    private companion object {
        const val ENVELOPE_RESERVE = 64
        const val MAX_BATCH_EVENTS = 32
    }
}
