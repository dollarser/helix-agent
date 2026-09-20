package com.helix.runtime.proot.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Strict versioned metadata only. PTY bytes, credentials and process memory are never journalled. */
object PtySessionRecordCodec {
    const val MAX_BYTES = 32 * 1024
    private const val VERSION = 1

    fun encode(record: PtySessionRecord): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(VERSION)
            with(record.origin) {
                listOf(sessionId, generation, executionId, workspace, runtimeGeneration).forEach(data::writeUTF)
                data.writeInt(bootCount ?: -1)
                data.writeLong(createdAtEpochMs)
                data.writeLong(startedAtElapsedMs)
                data.writeLong(deadlineElapsedMs)
            }
            data.writeUTF(record.phase.name)
            data.writeBoolean(record.process != null)
            record.process?.let {
                data.writeInt(it.pid)
                data.writeLong(it.startTicks)
            }
            data.writeUTF(record.stopReason?.name.orEmpty())
            data.writeUTF(record.stopProof?.name.orEmpty())
            data.writeInt(record.exitStatus ?: -1)
            data.writeBoolean(record.reconciled)
            data.writeInt(record.stoppedAtBootCount ?: -1)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    private fun DataInputStream.strictBoolean(): Boolean {
        val value = readUnsignedByte()
        require(value in 0..1) { "Invalid PTY journal boolean" }
        return value == 1
    }

    fun decode(bytes: ByteArray): PtySessionRecord {
        require(bytes.size in 1..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) { "Unsupported PTY journal version" }
            val origin =
                PtySessionOrigin(
                    data.readUTF(),
                    data.readUTF(),
                    data.readUTF(),
                    data.readUTF(),
                    data.readUTF(),
                    data.readInt().also { require(it >= -1) }.takeIf { it >= 0 },
                    data.readLong(),
                    data.readLong(),
                    data.readLong(),
                )
            val record =
                PtySessionRecord(
                    origin,
                    PtySessionRecord.Phase.valueOf(data.readUTF()),
                    if (data.strictBoolean()) PtyProcessIdentity(data.readInt(), data.readLong()) else null,
                    data.readUTF().takeIf { it.isNotEmpty() }?.let(PtySessionRecord.StopReason::valueOf),
                    data.readUTF().takeIf { it.isNotEmpty() }?.let(PtySessionRecord.StopProof::valueOf),
                    data.readInt().also { require(it >= -1) }.takeIf { it >= 0 },
                    data.strictBoolean(),
                    data.readInt().also { require(it >= -1) }.takeIf { it >= 0 },
                )
            require(data.read() == -1) { "Trailing PTY journal bytes" }
            record
        }
    }
}
