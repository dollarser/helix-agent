package com.helix.runtime.proot.ipc

import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * The bounded job spec the main app submits (HXA-084; architecture doc section
 * 6.5). `argv` and [script] are mutually exclusive: the runner receives argv and
 * ONLY uses an explicit shell script when the caller (after approval) asked for
 * shell syntax — a command is never assembled into one unescaped string here.
 *
 * The environment is the ALREADY-SCREENED allowlist (screening happens in the
 * main process before this object exists — see the client's screening; the
 * Runtime never sees SecretStore access).
 */
sealed interface ProotJobCommand {
    /** Direct argv execution (the default). */
    data class Argv(
        val arguments: List<String>,
    ) : ProotJobCommand

    /** An explicit shell script (only when shell syntax was explicitly requested). */
    data class Script(
        val script: String,
    ) : ProotJobCommand
}

data class ProotJobSpec(
    val executionId: String,
    val jobId: String,
    val command: ProotJobCommand,
    /** Relative to the job workspace; validated as a strict relative path. */
    val relativeWorkingDirectory: String,
    val environment: Map<String, String>,
    /** Hard deadline for the whole execution (watchdog-enforced, process-group kill). */
    val deadlineMs: Long,
    /** Combined stdout+stderr capture cap; exceeding it cancels the job. */
    val maxOutputBytes: Long,
    /** The hash the main app will hold the Runtime to (input archive manifest). */
    val inputManifestSha256: String,
    /** Optional regular file in the extracted workspace connected to process stdin. */
    val stdinRelativePath: String? = null,
    /** Per-stream stderr cap; the combined [maxOutputBytes] cap still applies. */
    val maxStderrBytes: Long = maxOutputBytes,
) {
    init {
        ProotJobRecordCodec.checkExecutionId(executionId)
        ProotJobRecordCodec.checkJobId(jobId)
        require(relativeWorkingDirectory.length in 0..256) { "relativeWorkingDirectory too long" }
        if (relativeWorkingDirectory.isNotEmpty()) {
            require(relativeWorkingDirectory.none { it == '/' || it == '\\' }) {
                "relativeWorkingDirectory must be a single safe segment"
            }
            require(relativeWorkingDirectory != "." && relativeWorkingDirectory != "..") {
                "relativeWorkingDirectory must be a plain name"
            }
            require(
                relativeWorkingDirectory.all {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' ||
                        it == '-'
                },
            ) {
                "relativeWorkingDirectory has invalid characters"
            }
        }
        require(environment.size <= 64) { "environment exceeds the entry cap" }
        require(environment.entries.all { (k, v) -> k.length in 1..128 && v.length in 0..8192 }) {
            "environment entry out of bounds"
        }
        require(deadlineMs in 1_000L..3_600_000L) { "deadlineMs out of bounds" }
        require(maxOutputBytes in 1_024L..(64L * 1024L * 1024L)) { "maxOutputBytes out of bounds" }
        require(maxStderrBytes in 1_024L..maxOutputBytes) { "maxStderrBytes out of bounds" }
        stdinRelativePath?.let { path ->
            require(path.length in 1..256) { "stdinRelativePath too long" }
            require(path.none { it == '\\' }) { "stdinRelativePath must use forward slashes" }
            require(
                path.split('/').all { segment ->
                    segment.isNotEmpty() && segment != "." && segment != ".." &&
                        segment.all { char ->
                            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                                char == '_' || char == '-' || char == '.'
                        }
                },
            ) { "stdinRelativePath must be a strict relative path" }
        }
        when (command) {
            is ProotJobCommand.Argv -> {
                require(command.arguments.size in 1..256) { "argv out of bounds" }
            }

            is ProotJobCommand.Script -> {
                require(command.script.length in 1..(256L * 1024L).toInt()) { "script out of bounds" }
            }
        }
        require(
            command.let {
                if (it is ProotJobCommand.Argv) {
                    it.arguments.all { a -> a.length in 1..8192 }
                } else {
                    true
                }
            },
        ) { "argv entry out of bounds" }
    }
}

/** A stable submit-time refusal (closed set; never carries paths or raw data). */
enum class ProotJobRefusal(
    val wire: String,
) {
    /** The journal quota (128 entries / 1 MiB) is full of non-evictable records. */
    JOURNAL_FULL("JOURNAL_FULL"),

    /** The spec failed validation on the server side (defense in depth). */
    INVALID_SPEC("INVALID_SPEC"),

    /** The Runtime has no active installation (not installed / not activated). */
    RUNTIME_NOT_READY("RUNTIME_NOT_READY"),
}

/**
 * Parcel marshalling of the job protocol (hand-rolled, same style as the
 * handshake: strict field order, both ends enforce it). Android's Parcel is not
 * available on the JVM, so this file is device-verified (E2E tests) while the
 * spec/record types it carries are JVM-tested in the client and companion.
 */
object ProotJobWire {
    const val MODE_ARGV: Int = 0
    const val MODE_SCRIPT: Int = 1

    fun writeSpec(
        parcel: Parcel,
        spec: ProotJobSpec,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
    ) {
        parcel.writeString(spec.executionId)
        parcel.writeString(spec.jobId)
        parcel.writeString(spec.inputManifestSha256)
        parcel.writeLong(spec.deadlineMs)
        parcel.writeLong(spec.maxOutputBytes)
        parcel.writeLong(spec.maxStderrBytes)
        parcel.writeString(spec.relativeWorkingDirectory)
        parcel.writeString(spec.stdinRelativePath)
        parcel.writeInt(spec.environment.size)
        spec.environment.toSortedMap().forEach { (name, value) ->
            parcel.writeString(name)
            parcel.writeString(value)
        }
        when (spec.command) {
            is ProotJobCommand.Argv -> {
                parcel.writeInt(MODE_ARGV)
                parcel.writeInt(spec.command.arguments.size)
                spec.command.arguments.forEach { parcel.writeString(it) }
            }

            is ProotJobCommand.Script -> {
                parcel.writeInt(MODE_SCRIPT)
                parcel.writeString(spec.command.script)
            }
        }
        parcel.writeFileDescriptor(inputPfd.fileDescriptor)
        parcel.writeFileDescriptor(outputPfd.fileDescriptor)
    }

    /**
     * Reads and VALIDATES one spec from the wire. Any structural anomaly throws
     * [ProotIpcException] — the server maps that to the INVALID_SPEC refusal and
     * closes the PFDs it already took.
     */
    @Suppress("ThrowsCount") // one throw per distinct wire violation

    fun readSpec(parcel: Parcel): ProotJobSpec {
        fun string(): String {
            val value =
                parcel.readString()
                    ?: throw ProotIpcException("job spec string is null")
            return value
        }

        val executionId = string()
        val jobId = string()
        val inputManifestSha256 = string()
        val deadlineMs = parcel.readLong()
        val maxOutputBytes = parcel.readLong()
        val maxStderrBytes = parcel.readLong()
        val relativeWorkingDirectory = string()
        val stdinRelativePath = parcel.readString()
        val envCount = parcel.readInt()
        if (envCount !in 0..64) throw ProotIpcException("job spec env count out of bounds")
        val environment = LinkedHashMap<String, String>()
        repeat(envCount) {
            val name = string()
            val value = string()
            if (name in environment) throw ProotIpcException("job spec env name duplicated")
            environment[name] = value
        }
        val command =
            when (val mode = parcel.readInt()) {
                MODE_ARGV -> {
                    val argc = parcel.readInt()
                    if (argc !in 1..256) throw ProotIpcException("job spec argc out of bounds")
                    val argv = ArrayList<String>(argc)
                    repeat(argc) { argv.add(string()) }
                    ProotJobCommand.Argv(argv)
                }

                MODE_SCRIPT -> {
                    ProotJobCommand.Script(string())
                }

                else -> {
                    throw ProotIpcException("job spec mode invalid: $mode")
                }
            }
        val spec =
            ProotJobSpec(
                executionId,
                jobId,
                command,
                relativeWorkingDirectory,
                environment,
                deadlineMs,
                maxOutputBytes,
                inputManifestSha256,
                stdinRelativePath,
                maxStderrBytes,
            )
        // The constructor re-validates every bound; a violation is INVALID_SPEC.
        return spec
    }

    /** Reads the two PFDs (input read, output write); the caller owns them from here. */
    fun readPfds(parcel: Parcel): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val inputFileDescriptor =
            parcel.readFileDescriptor()
                ?: throw ProotIpcException("job spec input PFD is null")
        val outputFileDescriptor =
            parcel.readFileDescriptor()
                ?: throw ProotIpcException("job spec output PFD is null")
        // The parcel already dups the fds across the process boundary; each end
        // owns its copy, so we take ownership (fromFd does NOT dup) and the
        // FileDescriptor returned by the parcel is the one to wrap.
        val input = ParcelFileDescriptor(inputFileDescriptor)
        val output = ParcelFileDescriptor(outputFileDescriptor)
        return input to output
    }

    /** Writes a job reply: status byte + a payload (record JSON or refusal wire). */
    fun writeJobReply(
        parcel: Parcel,
        status: Byte,
        payload: String?,
    ) {
        parcel.writeNoException()
        parcel.writeByte(status)
        parcel.writeString(payload)
    }

    /** Reads a job reply; the payload is null for NOT_FOUND. */
    fun readJobReply(parcel: Parcel): Pair<Byte, String?> {
        parcel.readException()
        val status = parcel.readByte()
        val payload = parcel.readString()
        if (status == ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND && payload != null) {
            throw ProotIpcException("NOT_FOUND reply carries a payload")
        }
        return status to payload
    }
}
