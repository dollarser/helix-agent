@file:Suppress(
    "LongMethod", // one full cross-APK job scenario (submit, poll, verify, reconcile)
    "TooManyFunctions", // the scenario list is the test class's purpose
)

package com.helix.app.proot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.mcp.McpStdioJobBridge
import com.helix.extensions.mcp.McpStdioLimits
import com.helix.extensions.mcp.McpStdioServerCommand
import com.helix.runtime.proot.client.ProotEnvScreen
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.core.sha256Hex
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRefusal
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * HXA-084 device acceptance, the CROSS-APK job E2E: the developer build of
 * com.helix.agent submits a REAL job through the ProotJobClient (cold binds),
 * the companion executes it under PRoot, and the main app recovers the
 * verified output archive — plus the screen/duplicate/not-found contracts the
 * runner tests exercise from the companion side.
 *
 * Warm-up requirement is the same as the HXA-083 E2E: the companion must be
 * installed and NOT force-stopped (scripts/accept-hxa-083-lifecycle.sh).
 */
@RunWith(AndroidJUnit4::class)
class ProotJobE2eDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val supervisor = ProotRuntimeSupervisor(context)
    private val client = ProotJobClient(supervisor)

    private val scratch = mutableListOf<File>()
    private val counter =
        java.util.concurrent.atomic
            .AtomicLong()

    @Before
    fun warm() {
        assumeTrue(
            "companion not installed — install runtime/proot-app/.../proot-app-debug.apk",
            companionInstalled(context),
        )
        assumeTrue(
            "companion is force-stopped (fresh install?) — warm it via scripts/accept-hxa-083-lifecycle.sh",
            !probeStoppedState(context),
        )
    }

    @After
    fun cleanScratch() {
        scratch.forEach { it.deleteRecursively() }
        scratch.clear()
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun aJobSubmittedFromTheMainAppRunsUnderProotAndReturnsVerifiedOutput() {
        runOnWorker(timeoutMs = 180_000L) {
            val (archive, inputSha) = buildInputArchive(mapOf("in.txt" to "IN-FROM-MAIN-APP"))
            val jobId = nextJobId()
            val spec =
                ProotJobSpec(
                    executionId = "exec-e2e-$jobId",
                    jobId = jobId,
                    command =
                        ProotJobCommand.Argv(
                            listOf(
                                "/bin/sh",
                                "-c",
                                "cat /workspace/in.txt > in.txt && echo artifact > out.txt && " +
                                    "echo MAIN_APP_OUT && echo MAIN_APP_ERR >&2",
                            ),
                        ),
                    relativeWorkingDirectory = "",
                    environment = screenOrThrow(mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/root")),
                    deadlineMs = 60_000L,
                    maxOutputBytes = 1_048_576L,
                    inputManifestSha256 = inputSha,
                )
            val inputPfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
            val outputFile = File(scratchDir("out"), "output.zip")
            val outputPfd =
                ParcelFileDescriptor.open(
                    outputFile,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            val accepted =
                client.submit(spec, inputPfd, outputPfd) as ProotJobClient.SubmitOutcome.Accepted
            assertEquals(jobId, accepted.record.jobId)

            // Poll for the terminal state through the client (cold binds each poll).
            val terminal =
                client.awaitTerminal(jobId, pollIntervalMs = 500L, timeoutMs = 120_000L) as
                    ProotJobClient.AwaitOutcome.Terminal
            val record = terminal.record
            assertEquals("SUCCEEDED", record.state.name)
            assertEquals(0, record.exitCode)
            assertNotNull("a success carries the output manifest hash", record.outputManifestSha256)
            assertTrue("stdout must be captured", record.stdoutBytes > 0)
            assertTrue("stderr must be captured", record.stderrBytes > 0)

            // The output archive must exist, extract cleanly, and verify.
            assertTrue(outputFile.isFile)
            assertTrue(outputFile.length() > 0)
            val extractedDir = scratchDir("extracted")
            val extraction = ZipJobExtractor.extract(outputFile, extractedDir)
            assertEquals(record.outputManifestSha256, extraction.manifestSha256)
            val paths =
                extraction.manifest.entries
                    .map { it.path }
                    .toSet()
            assertTrue("out.txt must be in the output", "out.txt" in paths)
            assertEquals("artifact\n", File(extractedDir, "out.txt").readText())
            assertEquals("MAIN_APP_OUT\n", File(extractedDir, "stdout.txt").readText())

            // Reconcile: the payload is deleted immediately; the proof remains.
            val reconciled = client.reconcile(jobId) as ProotJobClient.JobStateOutcome.Ok
            assertNotNull(reconciled.record.reconciledAtEpochMs)
            assertEquals(record.outputManifestSha256, reconciled.record.outputManifestSha256)
            Unit
        }
    }

    @Test
    fun aResubmittedJobIdReturnsTheExistingRecordAndNeverRestarts() {
        runOnWorker(timeoutMs = 180_000L) {
            val (archive, inputSha) = buildInputArchive(emptyMap())
            val jobId = nextJobId()
            val spec =
                ProotJobSpec(
                    executionId = "exec-e2e-$jobId",
                    jobId = jobId,
                    command = ProotJobCommand.Argv(listOf("/bin/sh", "-c", "true")),
                    relativeWorkingDirectory = "",
                    environment = screenOrThrow(emptyMap()),
                    deadlineMs = 60_000L,
                    maxOutputBytes = 1_048_576L,
                    inputManifestSha256 = inputSha,
                )

            fun submit(spec: ProotJobSpec): ProotJobClient.SubmitOutcome {
                val inputPfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
                val outputPfd =
                    ParcelFileDescriptor.open(
                        File(scratchDir("dup-out"), "output.zip"),
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                return client.submit(spec, inputPfd, outputPfd)
            }

            val first = submit(spec)
            assertTrue("first submit must be accepted: $first", first is ProotJobClient.SubmitOutcome.Accepted)
            val duplicate = submit(spec)
            assertTrue("resubmit must be a duplicate: $duplicate", duplicate is ProotJobClient.SubmitOutcome.Duplicate)
            assertEquals(jobId, (duplicate as ProotJobClient.SubmitOutcome.Duplicate).record.jobId)
            Unit
        }
    }

    @Test
    fun anUnknownJobIdIsStablyReportedUnknown() {
        runOnWorker {
            val unknown = client.query(nextJobId())
            assertEquals(ProotJobClient.JobStateOutcome.Unknown, unknown)
            val cancelUnknown = client.cancel(nextJobId())
            assertEquals(ProotJobClient.JobStateOutcome.Unknown, cancelUnknown)
            Unit
        }
    }

    @Test
    fun theEnvironmentScreenRefusesSecretsBeforeTheWire() {
        // The screen is pure (JVM-tested too); here it guards the client path:
        // the caller must not be able to hand a screened-out environment to the
        // Runtime, so the E2E asserts the screen's verdict at the boundary.
        val verdict =
            ProotEnvScreen.screen(
                mapOf(
                    "HOME" to "/root",
                    "GITHUB_TOKEN" to "should-never-reach-the-runtime",
                ),
                knownSecretValues = emptySet(),
            )
        val rejected = verdict as ProotEnvScreen.Verdict.Rejected
        assertEquals(
            ProotEnvScreen.Reason(ProotEnvScreen.Reason.Kind.SECRET_NAME, "GITHUB_TOKEN"),
            rejected.reasons.single(),
        )
        val approved = screenOrThrow(mapOf("HOME" to "/root"))
        assertEquals(mapOf("HOME" to "/root"), approved)
    }

    // ------------------------------------------------------------------ HXA-073 stdio

    @Test
    fun mcpStdioUsesLockedArgvStrictJsonRpcAndReconcilesTheJob() {
        runOnWorker(timeoutMs = 180_000L) {
            val jobId = nextJobId()
            val bridge = stdioBridge(jobId)
            val command =
                McpStdioServerCommand.locked(
                    listOf(
                        "/bin/sh",
                        "-c",
                        "IFS= read -r init; IFS= read -r ready; IFS= read -r list; " +
                            "case \"\$init\" in *initialize*) ;; *) exit 21;; esac; " +
                            "case \"\$ready\" in *notifications/initialized*) ;; *) exit 22;; esac; " +
                            "case \"\$list\" in *tools/list*) ;; *) exit 23;; esac; " +
                            "printf '%s\\n' " +
                            "'{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}' " +
                            "'{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}'; " +
                            "printf 'fixture diagnostic\\n' >&2",
                    ),
                )
            val outcome =
                bridge.exchange(
                    command,
                    listOf(
                        request(1, "initialize"),
                        notification("notifications/initialized"),
                        request(2, "tools/list"),
                    ),
                    environment = mapOf("PATH" to "/usr/bin:/bin"),
                ) as McpStdioJobBridge.Outcome.Completed

            assertEquals(2, outcome.output.messages.size)
            assertEquals("fixture diagnostic\n", outcome.output.stderr)
            assertEquals(command.fingerprintSha256, McpStdioServerCommand.locked(command.arguments).fingerprintSha256)
            assertNotNull("the verified stdio job must be reconciled", outcome.record.reconciledAtEpochMs)
            assertEquals(jobId, outcome.record.jobId)
            Unit
        }
    }

    @Test
    fun mcpStdioCancellationKillsAndReconcilesTheRuntimeJob() {
        runOnWorker(timeoutMs = 180_000L) {
            val jobId = nextJobId()
            val startedAt = System.currentTimeMillis()
            val outcome =
                stdioBridge(jobId).exchange(
                    McpStdioServerCommand.locked(listOf("/bin/sh", "-c", "IFS= read -r line; sleep 60")),
                    listOf(request(1, "initialize")),
                    environment = emptyMap(),
                    shouldContinue = { System.currentTimeMillis() - startedAt < 2_000L },
                ) as McpStdioJobBridge.Outcome.Cancelled

            assertEquals(com.helix.runtime.proot.ipc.ProotJobState.CANCELLED, outcome.record?.state)
            assertNotNull("cancelled stdio job must be reconciled", outcome.record?.reconciledAtEpochMs)
            Unit
        }
    }

    @Test
    fun mcpStdioRejectsServerLogNoiseOnStdout() {
        runOnWorker(timeoutMs = 180_000L) {
            val outcome =
                stdioBridge(nextJobId()).exchange(
                    McpStdioServerCommand.locked(
                        listOf(
                            "/bin/sh",
                            "-c",
                            "IFS= read -r line; printf 'server started\\n'; " +
                                "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\\n'",
                        ),
                    ),
                    listOf(request(1, "initialize")),
                    environment = emptyMap(),
                ) as McpStdioJobBridge.Outcome.Failed

            assertEquals("PROTOCOL_INVALID", outcome.code)
            assertEquals(com.helix.runtime.proot.ipc.ProotJobState.SUCCEEDED, outcome.record?.state)
            assertEquals(null, outcome.record?.reconciledAtEpochMs)
            Unit
        }
    }

    @Test
    fun mcpStdioStderrFloodIsKilledAtItsOwnCap() {
        runOnWorker(timeoutMs = 180_000L) {
            val startedAt = System.currentTimeMillis()
            val outcome =
                stdioBridge(nextJobId()).exchange(
                    McpStdioServerCommand.locked(
                        listOf("/bin/sh", "-c", "IFS= read -r line; yes diagnostic | head -c 4096 >&2; sleep 60"),
                    ),
                    listOf(request(1, "initialize")),
                    environment = emptyMap(),
                    limits =
                        McpStdioLimits(
                            maxLineBytes = 256,
                            maxStdoutBytes = 256,
                            maxStderrBytes = 1024,
                        ),
                ) as McpStdioJobBridge.Outcome.Failed

            assertEquals("JOB_OUTPUT_LIMIT_EXCEEDED", outcome.code)
            assertEquals(com.helix.runtime.proot.ipc.ProotJobState.OUTPUT_LIMIT_EXCEEDED, outcome.record?.state)
            assertTrue("stderr must stay at its configured cap", outcome.record?.stderrBytes in 1L..1024L)
            assertTrue(
                "stderr cap must kill the long-lived server promptly",
                System.currentTimeMillis() - startedAt < 15_000L,
            )
            Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun screenOrThrow(environment: Map<String, String>): Map<String, String> {
        val verdict = ProotEnvScreen.screen(environment, knownSecretValues = emptySet())
        val approved =
            verdict as? ProotEnvScreen.Verdict.Approved
                ?: error("environment must pass the screen: $verdict")
        return approved.environment
    }

    private fun stdioBridge(jobId: String): McpStdioJobBridge =
        McpStdioJobBridge(
            client = client,
            scratchRoot = scratchDir("mcp-stdio"),
            jobIdProvider = { jobId },
            knownSecretValues = { emptySet() },
        )

    private fun request(
        id: Int,
        method: String,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
    }

    private fun notification(method: String) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }

    private fun scratchDir(name: String): File =
        File(context.cacheDir, "jobe2e-$name-${nextJobId()}").apply {
            mkdirs()
            scratch += this
        }

    /** `job_` + exactly 12 hex digits (the wire-validated jobId form). */
    private fun nextJobId(): String =
        "job_" +
            (System.nanoTime() * 31 + counter.incrementAndGet())
                .toUInt()
                .toString(16)
                .padStart(12, '0')
                .takeLast(12)

    private fun buildInputArchive(files: Map<String, String>): Pair<File, String> {
        val dir = scratchDir("in")
        val entries =
            files
                .map { (path, content) ->
                    File(dir, path).apply { writeText(content) }
                    JobManifestEntry(
                        path,
                        sha256Hex(MessageDigest.getInstance("SHA-256").apply { update(content.toByteArray()) }),
                        content.toByteArray().size.toLong(),
                    )
                }.sortedBy { it.path }
        val document = JobManifestCodec.encode(JobManifest(entries))
        val archive = File(dir, "input.zip")
        JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(document)
            entries.forEach { entry -> writer.writeEntry(entry.path, File(dir, entry.path)) }
        }
        return archive to sha256OfFileBytes(document.toByteArray())
    }

    private fun sha256OfFileBytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

// ----------------------------------------------------------------------
// File-level helpers (mirrors the HXA-083 E2E's conventions).
// ----------------------------------------------------------------------

// @Suppress("SwallowedException") — a NameNotFound IS the answer.
@Suppress("SwallowedException")
private fun companionAppInfo(context: Context): ApplicationInfo? =
    try {
        context.packageManager.getPackageInfo(ProotRuntimeProtocol.RUNTIME_PACKAGE, 0).applicationInfo
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

private fun companionInstalled(context: Context): Boolean = companionAppInfo(context) != null

private fun probeStoppedState(context: Context): Boolean {
    val appInfo = companionAppInfo(context) ?: return false
    return appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
}

@Suppress("TooGenericExceptionCaught", "ThrowsCount")
private fun <T> runOnWorker(
    timeoutMs: Long = 60_000L,
    block: () -> T,
): T {
    val future = CompletableFuture<T>()
    Thread {
        try {
            future.complete(block())
        } catch (e: Throwable) {
            future.completeExceptionally(e)
        }
    }.start()
    return try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        throw IllegalStateException("worker interrupted", e)
    } catch (e: java.util.concurrent.TimeoutException) {
        throw IllegalStateException("worker exceeded ${timeoutMs}ms", e)
    }
}
