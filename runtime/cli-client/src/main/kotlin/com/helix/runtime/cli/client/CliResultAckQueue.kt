package com.helix.runtime.cli.client

import java.io.File

/** Durable receipt delivery only. Contains identities, never prompts, output bytes, or execution authority. */
class CliResultAckQueue(
    private val root: File,
) {
    private val files = CliJobRecordFile()

    fun enqueue(record: CliModelJobRecord) =
        synchronized(lock) {
            require(record.state.terminal && record.state != CliModelJobState.EVIDENCE_EXPIRED)
            val pending = record.copy(reconciledAtEpochMillis = null)
            val file = file(record.jobId)
            val existing = files.read(file)
            require(existing == null || existing == pending) { "Conflicting result acknowledgement" }
            if (existing == null) files.write(file, pending)
        }

    fun pending(): List<String> =
        synchronized(lock) {
            if (!root.exists()) {
                emptyList()
            } else {
                check(root.isDirectory) { "Invalid acknowledgement store" }
                requireNotNull(root.listFiles())
                    .asSequence()
                    .filter { it.name.matches(Regex("job_[0-9a-f]{12}\\.json")) }
                    .map { it.name.removeSuffix(".json") }
                    .sorted()
                    .take(128)
                    .toList()
            }
        }

    /** A lost ACK reply is retried against the same terminal identity; no submit API is available here. */
    fun attempt(
        jobId: String,
        acknowledge: (CliModelJobRecord) -> CliModelJobClient.StateOutcome,
    ): Boolean {
        val expected = synchronized(lock) { files.read(file(jobId)) } ?: return true
        require(expected.jobId == jobId) { "Acknowledgement journal identity mismatch" }
        val reply = acknowledge(expected) as? CliModelJobClient.StateOutcome.Ok
        val confirmed =
            reply?.record?.let {
                it.reconciledAtEpochMillis != null && it.copy(reconciledAtEpochMillis = null) == expected
            } == true
        return confirmed && removeAcknowledged(expected)
    }

    private fun removeAcknowledged(expected: CliModelJobRecord): Boolean =
        synchronized(lock) {
            val file = file(expected.jobId)
            val current = files.read(file)
            if (current == null) {
                true
            } else {
                check(current == expected) { "Acknowledgement identity changed" }
                check(file.delete()) { "Acknowledgement cleanup failed" }
                true
            }
        }

    private fun file(jobId: String): File {
        CliModelJobRecord.checkJobId(jobId)
        return File(root, "$jobId.json")
    }

    private companion object {
        val lock = Any()
    }
}
