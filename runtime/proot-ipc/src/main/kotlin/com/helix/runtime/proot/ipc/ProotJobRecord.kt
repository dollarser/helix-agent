package com.helix.runtime.proot.ipc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The Runtime's job journal record (HXA-084; architecture doc section 6.7). The
 * companion is the SOLE writer: it atomically maintains one record per
 * executionId/jobId and never silently deletes an active or unreconciled
 * terminal record. The record is the reconciliation proof: after a Binder
 * disconnect the main app may only QUERY, and a terminal record whose
 * [inputManifestSha256] matches is the only thing that can restore a result.
 *
 * [terminalCommit] is the SHA-256 of the canonical committed body (the terminal
 * outcome fields, WITHOUT the commit itself and WITHOUT the reconciliation
 * bookkeeping) — the stable identity of the outcome that both sides compare.
 */
enum class ProotJobState(
    val wire: String,
) {
    PENDING("PENDING"),
    RUNNING("RUNNING"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    TIMED_OUT("TIMED_OUT"),
    CANCELLED("CANCELLED"),
    OUTPUT_LIMIT_EXCEEDED("OUTPUT_LIMIT_EXCEEDED"),
    INPUT_INVALID("INPUT_INVALID"),
    ORPHANED("ORPHANED"),
    ;

    val isTerminal: Boolean
        get() = this != PENDING && this != RUNNING

    companion object {
        fun fromWire(wire: String): ProotJobState =
            entries.firstOrNull { it.wire == wire }
                ?: throw ProotIpcException("unknown job state: $wire")
    }
}

data class ProotJobRecord(
    val jobId: String,
    val executionId: String,
    val inputManifestSha256: String,
    val state: ProotJobState,
    val createdAtEpochMs: Long,
    val terminalAtEpochMs: Long? = null,
    val exitCode: Int? = null,
    val stdoutBytes: Long = 0L,
    val stderrBytes: Long = 0L,
    val truncated: Boolean = false,
    val outputManifestSha256: String? = null,
    val reconciledAtEpochMs: Long? = null,
    val evidenceExpired: Boolean = false,
) {
    init {
        ProotJobRecordCodec.checkJobId(jobId)
        ProotJobRecordCodec.checkExecutionId(executionId)
        require(inputManifestSha256.length == 64 && inputManifestSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "inputManifestSha256 is not canonical lowercase hex"
        }
        require(createdAtEpochMs in 0..Long.MAX_VALUE) { "createdAtEpochMs out of bounds" }
        require(stdoutBytes in 0..ProotJobRecordCodec.MAX_FIELD_BYTES) { "stdoutBytes out of bounds" }
        require(stderrBytes in 0..ProotJobRecordCodec.MAX_FIELD_BYTES) { "stderrBytes out of bounds" }
        if (outputManifestSha256 != null) {
            require(
                outputManifestSha256.length == 64 &&
                    outputManifestSha256.all { it in '0'..'9' || it in 'a'..'f' },
            ) { "outputManifestSha256 is not canonical lowercase hex" }
        }
        if (state.isTerminal) {
            require(terminalAtEpochMs != null && terminalAtEpochMs >= createdAtEpochMs) {
                "terminal record needs a consistent terminalAtEpochMs"
            }
        } else {
            require(terminalAtEpochMs == null && reconciledAtEpochMs == null && !evidenceExpired) {
                "non-terminal record carries terminal bookkeeping"
            }
        }
        if (reconciledAtEpochMs != null) require(state.isTerminal) { "reconciled non-terminal record" }
        // exitCode is REQUIRED only for SUCCEEDED (a success that never reports its
        // exit is not a proof); launch failures and pre-start cancellations have no
        // process, so FAILED/TIMED_OUT/CANCELLED/OUTPUT_LIMIT_EXCEEDED carry it when
        // the process existed. ORPHANED/INPUT_INVALID never did.
        when (state) {
            ProotJobState.SUCCEEDED -> {
                require(exitCode != null) { "state $state requires exitCode" }
            }

            ProotJobState.ORPHANED,
            ProotJobState.INPUT_INVALID,
            -> {
                require(exitCode == null) { "state $state carries no exit" }
                require(outputManifestSha256 == null) { "state $state carries no output" }
            }

            else -> {}
        }
    }

    /**
     * The stable identity of a terminal outcome; null while the job is not
     * terminal. Reconciliation compares THIS, and the output archive's manifest
     * hash, against what the main app promised/verified.
     */
    val terminalCommit: String?
        get() =
            if (!state.isTerminal) {
                null
            } else {
                sha256(committedBody().encodeToByteArray())
            }

    /** Canonical JSON of the terminal outcome fields (the commit's input). */
    fun committedBody(): String {
        require(state.isTerminal) { "not terminal" }
        val obj =
            buildJsonObject {
                put("schemaVersion", ProotJobRecordCodec.SUPPORTED_SCHEMA_VERSION)
                put("jobId", jobId)
                put("executionId", executionId)
                put("inputManifestSha256", inputManifestSha256)
                put("state", state.wire)
                if (exitCode != null) put("exitCode", exitCode)
                put("stdoutBytes", stdoutBytes)
                put("stderrBytes", stderrBytes)
                put("truncated", truncated)
                if (outputManifestSha256 != null) put("outputManifestSha256", outputManifestSha256)
                put("terminalAtEpochMs", terminalAtEpochMs)
            }
        return obj.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/**
 * Strict fail-closed codec for [ProotJobRecord] (house JsonElement style). The
 * on-wire/on-disk document is the FULL record including [ProotJobRecord.terminalCommit];
 * parsing re-computes the commit and rejects a mismatch, so a tampered journal
 * entry is a protocol failure, never a trusted proof.
 */
object ProotJobRecordCodec {
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    /** Bounded field budget shared by journal metadata (1 MiB cap lives in the store). */
    const val MAX_FIELD_BYTES: Long = 1L * 1024L * 1024L

    private const val JOB_ID_PREFIX = "job_"
    private const val JOB_ID_HEX_LENGTH = 12
    private val jobIdPattern = Regex("${JOB_ID_PREFIX}[0-9a-f]{${JOB_ID_HEX_LENGTH}}")

    private val json = Json { isLenient = false }

    private fun isExecutionIdChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    /** jobId form: `job_` + 12 lowercase hex (mirrors the `inst_` convention). */
    fun checkJobId(jobId: String) {
        if (!jobIdPattern.matches(jobId)) throw ProotIpcException("malformed jobId: $jobId")
    }

    /**
     * executionId form: the main-app identifier charset (ASCII alnum + `_`/`-`),
     * bounded length — mirrors `core:model`'s Identifier rules without depending
     * on that module (proot-ipc is the shared protocol surface).
     */
    fun checkExecutionId(executionId: String) {
        if (
            executionId.isEmpty() ||
            executionId.length > 128 ||
            executionId.any { !isExecutionIdChar(it) }
        ) {
            throw ProotIpcException("malformed executionId")
        }
    }

    private val REQUIRED_KEYS =
        setOf(
            "schemaVersion",
            "jobId",
            "executionId",
            "inputManifestSha256",
            "state",
            "createdAtEpochMs",
            "stdoutBytes",
            "stderrBytes",
            "truncated",
            "evidenceExpired",
        )
    private val OPTIONAL_KEYS =
        setOf("terminalAtEpochMs", "exitCode", "outputManifestSha256", "terminalCommit", "reconciledAtEpochMs")

    // One throw per distinct schema violation; the parse is a single strict
    // read of a bounded document (long by necessity, not to be fragmented).
    @Suppress("ThrowsCount", "LongMethod", "CyclomaticComplexMethod", "SwallowedException")
    fun parse(document: String): ProotJobRecord {
        val obj =
            try {
                json.parseToJsonElement(document) as? JsonObject
                    ?: throw ProotIpcException("job record must be a JSON object")
            } catch (e: ProotIpcException) {
                throw e
            } catch (e: SerializationException) {
                throw ProotIpcException("job record is not valid JSON: ${e.message?.take(120)}", e)
            }
        obj.keys.filter { it !in REQUIRED_KEYS && it !in OPTIONAL_KEYS }.forEach { key ->
            throw ProotIpcException("job record has unknown key: $key")
        }
        REQUIRED_KEYS.filter { it !in obj.keys }.forEach { key ->
            throw ProotIpcException("job record is missing required key: $key")
        }
        val schemaVersion = long(obj, "schemaVersion").toInt()
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw ProotIpcException("unsupported job record schemaVersion: $schemaVersion")
        }
        val state = ProotJobState.fromWire(string(obj, "state"))
        val jobId = string(obj, "jobId")
        val executionId = string(obj, "executionId")
        val terminalAt = obj["terminalAtEpochMs"]?.let { long(obj, "terminalAtEpochMs") }
        val exitCode = obj["exitCode"]?.let { long(obj, "exitCode").toInt() }
        if (exitCode != null && exitCode !in 0..255) throw ProotIpcException("exitCode out of bounds")
        val outputManifest = obj["outputManifestSha256"]?.let { string(obj, "outputManifestSha256") }
        val reconciledAt = obj["reconciledAtEpochMs"]?.let { long(obj, "reconciledAtEpochMs") }

        val record =
            try {
                ProotJobRecord(
                    jobId,
                    executionId,
                    string(obj, "inputManifestSha256"),
                    state,
                    long(obj, "createdAtEpochMs"),
                    terminalAt,
                    exitCode,
                    long(obj, "stdoutBytes"),
                    long(obj, "stderrBytes"),
                    bool(obj, "truncated"),
                    outputManifest,
                    reconciledAt,
                    bool(obj, "evidenceExpired"),
                )
            } catch (e: IllegalArgumentException) {
                throw ProotIpcException("job record is incoherent: ${e.message?.take(120)}")
            }
        // State/field coherence beyond the constructor:
        if (state.isTerminal) {
            if (terminalAt == null) throw ProotIpcException("terminal record is missing terminalAtEpochMs")
            if (obj["terminalCommit"] == null) {
                throw ProotIpcException("terminal record is missing terminalCommit")
            }
            if (string(obj, "terminalCommit") != record.terminalCommit) {
                throw ProotIpcException("terminalCommit does not match the record")
            }
            when (state) {
                ProotJobState.SUCCEEDED -> {
                    if (exitCode == null) throw ProotIpcException("state $state requires exitCode")
                }

                ProotJobState.ORPHANED,
                ProotJobState.INPUT_INVALID,
                -> {
                    if (exitCode != null || outputManifest != null) {
                        throw ProotIpcException("state $state carries no exit or output")
                    }
                }

                else -> {}
            }
        } else {
            val carriesTerminal =
                terminalAt != null ||
                    exitCode != null ||
                    outputManifest != null ||
                    obj["terminalCommit"] != null
            if (carriesTerminal) throw ProotIpcException("non-terminal record carries terminal fields")
        }
        return record
    }

    /** Canonical encoding (fixed key order); the input must already be valid. */
    fun encode(record: ProotJobRecord): String {
        val obj =
            buildJsonObject {
                put("schemaVersion", SUPPORTED_SCHEMA_VERSION)
                put("jobId", record.jobId)
                put("executionId", record.executionId)
                put("inputManifestSha256", record.inputManifestSha256)
                put("state", record.state.wire)
                put("createdAtEpochMs", record.createdAtEpochMs)
                if (record.state.isTerminal) {
                    put("terminalAtEpochMs", record.terminalAtEpochMs)
                }
                if (record.exitCode != null) put("exitCode", record.exitCode)
                put("stdoutBytes", record.stdoutBytes)
                put("stderrBytes", record.stderrBytes)
                put("truncated", record.truncated)
                if (record.outputManifestSha256 != null) {
                    put("outputManifestSha256", record.outputManifestSha256)
                }
                val commit = record.terminalCommit
                if (commit != null) put("terminalCommit", commit)
                if (record.reconciledAtEpochMs != null) {
                    put("reconciledAtEpochMs", record.reconciledAtEpochMs)
                }
                put("evidenceExpired", record.evidenceExpired)
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun string(
        obj: JsonObject,
        key: String,
    ): String =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProotIpcException("job record.$key must be a string")

    private fun long(
        obj: JsonObject,
        key: String,
    ): Long =
        (obj[key] as? JsonPrimitive)?.longOrNull
            ?: throw ProotIpcException("job record.$key must be a long")

    private fun bool(
        obj: JsonObject,
        key: String,
    ): Boolean {
        val primitive =
            obj[key] as? JsonPrimitive
                ?: throw ProotIpcException("job record.$key must be a boolean")
        val value =
            when (primitive.content) {
                "true" -> true
                "false" -> false
                else -> throw ProotIpcException("job record.$key must be a boolean")
            }
        return value
    }
}
