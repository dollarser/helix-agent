package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelEventCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Request-owned event list with its index on disk; no cumulative event/text buffer in memory. */
internal class SpoolingModelEvents(
    directory: File,
) : AbstractList<ModelEvent>(),
    Closeable {
    private val dataFile: File
    private val indexFile: File
    override var size: Int = 0
        private set

    init {
        check(directory.mkdirs() || directory.isDirectory)
        dataFile = File.createTempFile("events-", ".tmp", directory)
        indexFile = File(dataFile.path + ".index")
    }

    fun append(events: List<ModelEvent>) {
        RandomAccessFile(dataFile, "rw").use { data ->
            RandomAccessFile(indexFile, "rw").use { index ->
                data.seek(data.length())
                index.seek(index.length())
                events.forEach { event ->
                    val bytes = CliModelEventCodec.encodeEvent(event).toString().encodeToByteArray()
                    index.writeLong(data.filePointer)
                    data.writeInt(bytes.size)
                    data.write(bytes)
                    size = Math.addExact(size, 1)
                }
            }
        }
    }

    override fun get(index: Int): ModelEvent {
        require(index in 0 until size)
        return RandomAccessFile(indexFile, "r").use { positions ->
            positions.seek(index.toLong() * java.lang.Long.BYTES)
            RandomAccessFile(dataFile, "r").use { data ->
                data.seek(positions.readLong())
                val length = data.readInt()
                require(length > 0 && length.toLong() <= data.length() - data.filePointer)
                val bytes = ByteArray(length)
                data.readFully(bytes)
                CliModelEventCodec.decodeEvent(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
            }
        }
    }

    override fun close() {
        val dataDeleted = !dataFile.exists() || dataFile.delete()
        val indexDeleted = !indexFile.exists() || indexFile.delete()
        check(dataDeleted && indexDeleted) { "event spool cleanup failed" }
    }
}

internal class MappedModelEvents(
    private val source: List<ModelEvent>,
    private val transform: (ModelEvent) -> ModelEvent,
) : AbstractList<ModelEvent>(),
    Closeable {
    override val size: Int get() = source.size

    override fun get(index: Int): ModelEvent = transform(source[index])

    override fun close() {
        (source as? Closeable)?.close()
    }
}
