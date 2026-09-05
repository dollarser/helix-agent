package com.helix.runtime.cli.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class CliModelJobState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;
    val terminal: Boolean get() = this !in setOf(PENDING, RUNNING)
}

data class CliModelJobRecord(
    val jobId: String,
    val requestSha256: String,
    val state: CliModelJobState,
    val createdAtEpochMillis: Long,
    val terminalAtEpochMillis: Long? = null,
    val model: String? = null,
    val outputSha256: String? = null,
) {
    init {
        checkJobId(jobId)
        require(SHA256.matches(requestSha256))
        require(state.terminal == (terminalAtEpochMillis != null))
        require(model == null || model.length in 1..128)
        require(outputSha256 == null || SHA256.matches(outputSha256))
        require(state != CliModelJobState.SUCCEEDED || (model != null && outputSha256 != null))
        require(state == CliModelJobState.SUCCEEDED || outputSha256 == null)
    }

    companion object {
        internal val JOB_ID = Regex("job_[0-9a-f]{12}")
        internal val SHA256 = Regex("[0-9a-f]{64}")
        fun checkJobId(value: String) { require(JOB_ID.matches(value)) }
    }
}

object CliModelJobRecordCodec {
    const val MAX_RECORD_BYTES = 8 * 1024

    fun encode(record: CliModelJobRecord): String = buildJsonObject {
        put("version", 1)
        put("jobId", record.jobId)
        put("requestSha256", record.requestSha256)
        put("state", record.state.name)
        put("createdAtEpochMillis", record.createdAtEpochMillis)
        record.terminalAtEpochMillis?.let { put("terminalAtEpochMillis", it) }
        record.model?.let { put("model", it) }
        record.outputSha256?.let { put("outputSha256", it) }
    }.toString().also { require(it.encodeToByteArray().size <= MAX_RECORD_BYTES) }

    fun decode(document: String): CliModelJobRecord {
        require(document.encodeToByteArray().size <= MAX_RECORD_BYTES)
        val obj = Json.parseToJsonElement(document).jsonObject
        val required = setOf("version", "jobId", "requestSha256", "state", "createdAtEpochMillis")
        val optional = setOf("terminalAtEpochMillis", "model", "outputSha256")
        require(obj.keys.containsAll(required) && obj.keys.all { it in required || it in optional })
        require(obj.getValue("version").jsonPrimitive.long == 1L)
        return CliModelJobRecord(
            obj.getValue("jobId").jsonPrimitive.content,
            obj.getValue("requestSha256").jsonPrimitive.content,
            CliModelJobState.valueOf(obj.getValue("state").jsonPrimitive.content),
            obj.getValue("createdAtEpochMillis").jsonPrimitive.long,
            obj["terminalAtEpochMillis"]?.jsonPrimitive?.long,
            obj["model"]?.jsonPrimitive?.content,
            obj["outputSha256"]?.jsonPrimitive?.content,
        )
    }
}
