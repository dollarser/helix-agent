package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import java.io.File

/** Commit the marker before deleting payloads so interrupted cleanup is repeatable without replay. */
internal class CodexEvidenceExpiry(
    private val jobs: File,
    private val load: (String) -> CliModelJobRecord?,
    private val put: (CliModelJobRecord) -> Unit,
) {
    fun expire(now: Long) {
        jobs.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val record = runCatching { load(directory.name) }.getOrNull() ?: return@forEach
            if (record.state == CliModelJobState.EVIDENCE_EXPIRED) {
                CodexPayloadFiles(jobs).delete(record.jobId)
            } else if (isExpired(record, now)) {
                put(record.copy(state = CliModelJobState.EVIDENCE_EXPIRED, model = null, outputSha256 = null))
                CodexPayloadFiles(jobs).delete(record.jobId)
            }
        }
    }

    private fun isExpired(
        record: CliModelJobRecord,
        now: Long,
    ): Boolean {
        val terminal = record.terminalAtEpochMillis ?: return false
        return record.reconciledAtEpochMillis == null && now >= terminal && now - terminal >= RETENTION_MS
    }

    companion object {
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
