package com.helix.app.mcp

import android.os.ParcelFileDescriptor
import com.helix.extensions.mcp.McpStdioLimits
import com.helix.extensions.mcp.McpStdioOutput
import com.helix.extensions.mcp.McpStdioProtocol
import com.helix.extensions.mcp.McpStdioServerCommand
import com.helix.runtime.proot.client.ProotEnvScreen
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.core.sha256Hex
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/**
 * Developer-only MCP stdio transport over the separately signed PRoot Runtime.
 *
 * One exchange is one durable Runtime Job. The persisted server configuration supplies
 * [McpStdioServerCommand]; request JSON is written only to a stdin snapshot and can never be
 * concatenated into argv. Binder loss is handled by job-id query, never by replay.
 */
class McpStdioJobBridge(
    private val client: ProotJobClient,
    private val scratchRoot: File,
    private val jobIdProvider: () -> String,
    private val knownSecretValues: () -> Set<String>,
) {
    sealed interface Outcome {
        data class Completed(
            val record: ProotJobRecord,
            val output: McpStdioOutput,
        ) : Outcome

        data class Cancelled(
            val record: ProotJobRecord?,
        ) : Outcome

        data class Failed(
            val code: String,
            val record: ProotJobRecord? = null,
        ) : Outcome
    }

    @Suppress(
        "TooGenericExceptionCaught", // every external failure maps to a stable value-free outcome
        "SwallowedException", // raw exceptions may contain paths or server output and must not cross the boundary
        "CyclomaticComplexMethod", // one explicit branch per submit/await/verify/reconcile state
        "LongMethod", // the exchange is one linear transaction with a single scratch lifetime
        "ReturnCount", // fail closed at the exact phase that lost its proof
    )
    fun exchange(
        serverCommand: McpStdioServerCommand,
        clientMessages: List<JsonObject>,
        environment: Map<String, String>,
        deadlineMs: Long = 60_000L,
        limits: McpStdioLimits = McpStdioLimits(),
        shouldContinue: () -> Boolean = { true },
    ): Outcome {
        if (!shouldContinue()) return Outcome.Cancelled(null)
        val screened = ProotEnvScreen.screen(environment, knownSecretValues())
        val approvedEnvironment =
            when (screened) {
                is ProotEnvScreen.Verdict.Approved -> screened.environment
                is ProotEnvScreen.Verdict.Rejected -> return Outcome.Failed("ENV_REFUSED")
            }
        val scratch = File(scratchRoot, "mcp-stdio-${System.nanoTime().toULong().toString(16)}")
        if (!scratch.mkdirs()) return Outcome.Failed("SCRATCH_FAILED")
        return try {
            val stdin =
                File(scratch, STDIN_PATH).apply {
                    parentFile?.mkdirs()
                    writeBytes(McpStdioProtocol.encodeClientMessages(clientMessages, limits))
                }
            val commandProof =
                File(scratch, COMMAND_PROOF_PATH).apply {
                    parentFile?.mkdirs()
                    writeText(serverCommand.fingerprintSha256)
                }
            val inputZip = File(scratch, "input.zip")
            val inputSha = buildInputArchive(listOf(STDIN_PATH to stdin, COMMAND_PROOF_PATH to commandProof), inputZip)
            val outputZip = File(scratch, "output.zip")
            val jobId = jobIdProvider()
            val spec =
                ProotJobSpec(
                    executionId = "exec_mcp_${jobId.removePrefix("job_")}",
                    jobId = jobId,
                    command = ProotJobCommand.Argv(serverCommand.arguments),
                    relativeWorkingDirectory = "",
                    environment = approvedEnvironment,
                    deadlineMs = deadlineMs,
                    maxOutputBytes = (limits.maxStdoutBytes + limits.maxStderrBytes).toLong(),
                    inputManifestSha256 = inputSha,
                    stdinRelativePath = STDIN_PATH,
                    maxStderrBytes = limits.maxStderrBytes.toLong(),
                )
            val submit =
                client.submit(
                    spec,
                    ParcelFileDescriptor.open(inputZip, ParcelFileDescriptor.MODE_READ_ONLY),
                    ParcelFileDescriptor.open(
                        outputZip,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    ),
                )
            when (submit) {
                is ProotJobClient.SubmitOutcome.Accepted,
                is ProotJobClient.SubmitOutcome.Duplicate,
                -> Unit

                is ProotJobClient.SubmitOutcome.Rejected -> return Outcome.Failed("JOB_${submit.refusal.wire}")

                is ProotJobClient.SubmitOutcome.Unavailable -> return Outcome.Failed("UNAVAILABLE_${submit.cause.name}")
            }
            val terminal =
                client.awaitTerminal(jobId, timeoutMs = deadlineMs + 30_000L, shouldContinue = shouldContinue)
            if (!shouldContinue()) {
                client.cancel(jobId)
                val settled = client.awaitTerminal(jobId, timeoutMs = 30_000L)
                val record = (settled as? ProotJobClient.AwaitOutcome.Terminal)?.record
                val reconciled =
                    record?.let {
                        (client.reconcile(jobId) as? ProotJobClient.JobStateOutcome.Ok)?.record
                    }
                return Outcome.Cancelled(reconciled ?: record)
            }
            val record =
                when (terminal) {
                    is ProotJobClient.AwaitOutcome.Terminal -> terminal.record

                    is ProotJobClient.AwaitOutcome.Interrupted -> return Outcome.Failed(
                        "EVIDENCE_EXPIRED",
                        terminal.record,
                    )

                    is ProotJobClient.AwaitOutcome.TimedOut -> return Outcome.Failed("INTERRUPTED_TIMEOUT")

                    is ProotJobClient.AwaitOutcome.Unknown -> return Outcome.Failed("INTERRUPTED_UNKNOWN")

                    is ProotJobClient.AwaitOutcome.Unavailable -> return Outcome.Failed(
                        "INTERRUPTED_${terminal.cause.name}",
                    )
                }
            if (record.state != ProotJobState.SUCCEEDED || record.truncated) {
                return Outcome.Failed("JOB_${record.state.wire}", record)
            }
            val expectedHash = record.outputManifestSha256 ?: return Outcome.Failed("OUTPUT_MANIFEST_MISSING", record)
            val extracted = File(scratch, "output")
            val extraction = ZipJobExtractor.extract(outputZip, extracted)
            if (extraction.manifestSha256 != expectedHash) return Outcome.Failed("OUTPUT_HASH_MISMATCH", record)
            val stdout = File(extracted, "stdout.txt").readBytes()
            val stderr = File(extracted, "stderr.txt").readBytes()
            if (stdout.size.toLong() != record.stdoutBytes || stderr.size.toLong() != record.stderrBytes) {
                return Outcome.Failed("OUTPUT_LENGTH_MISMATCH", record)
            }
            if (File(extracted, COMMAND_PROOF_PATH).readText() != serverCommand.fingerprintSha256) {
                return Outcome.Failed("COMMAND_PROOF_MISMATCH", record)
            }
            val output =
                try {
                    McpStdioProtocol.parseServerOutput(stdout, stderr, limits)
                } catch (e: IllegalArgumentException) {
                    return Outcome.Failed("PROTOCOL_INVALID", record)
                }
            when (val reconciled = client.reconcile(jobId)) {
                is ProotJobClient.JobStateOutcome.Ok -> Outcome.Completed(reconciled.record, output)
                else -> Outcome.Failed("RECONCILE_FAILED", record)
            }
        } catch (e: JobArchiveException) {
            Outcome.Failed("ARCHIVE_INVALID")
        } catch (e: IllegalArgumentException) {
            Outcome.Failed("PROTOCOL_INVALID")
        } catch (e: Exception) {
            Outcome.Failed("STDIO_FAILED")
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun buildInputArchive(
        files: List<Pair<String, File>>,
        archive: File,
    ): String {
        val byPath = files.toMap()
        val entries =
            files
                .map { (path, file) ->
                    JobManifestEntry(
                        path,
                        sha256Hex(MessageDigest.getInstance("SHA-256").apply { update(file.readBytes()) }),
                        file.length(),
                    )
                }.sortedBy { it.path }
        val document = JobManifestCodec.encode(JobManifest(entries))
        JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(document)
            entries.forEach { entry -> writer.writeEntry(entry.path, byPath.getValue(entry.path)) }
        }
        return sha256(document.encodeToByteArray())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val STDIN_PATH = "mcp/stdin.jsonl"
        const val COMMAND_PROOF_PATH = "mcp/command.sha256"
    }
}
