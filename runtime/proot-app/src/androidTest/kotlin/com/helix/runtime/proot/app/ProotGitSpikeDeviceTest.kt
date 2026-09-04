package com.helix.runtime.proot.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotJobSubmitResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-088 Spike device evidence (ADR-0008 "Required before acceptance"):
 *
 * 1. SMALL/MEDIUM `.git` repositories through the REAL 084 job pipeline
 *    (JobZipWriter input → guest git on the /workspace snapshot → JobZipWriter
 *    output): archive size, archive time, guest git operation time (job
 *    elapsed), output size/file count — the per-operation cost that decides
 *    Path 1 (main-App-owned repo, full atomic transaction per operation) and
 *    the per-component costs of Path 2 (Runtime-private repo: the guest git
 *    operation time is the same; the zip round-trips are what Path 2 avoids).
 * 2. Corruption recovery: a damaged object in a committed repository is
 *    REPORTED by git fsck (never silently clean) and the job state stays
 *    truthful.
 * 3. Object bloat: `git gc` packs the object DB — the archive budget for
 *    first-version repositories is therefore expressed in PACKED form.
 * 4. Process death / reconciliation: a job id resolves to exactly ONE stable
 *    terminal record (idempotent query — the property that makes the 086
 *    mid-job kill safe: no replay, no silent half-repo, the output boundary
 *    is all-or-nothing per archive).
 * 5. Attack fixtures: hostile hooks/filter/alias/submodule/credential-helper
 *    content carried in a snapshot — EVIDENCE of which mechanisms the first-
 *    version structured commands (status/diff/log/init/add/commit) DO trigger
 *    (hooks on commit, clean filters on add) and which are inert by
 *    construction (alias expansion, submodule recursion, credential prompt),
 *    plus the guard (`core.hooksPath=/dev/null`) that makes commit hook-safe.
 *    Path 2/Path 3 cannot be device-measured here (Path 2 needs a NEW bind
 *    surface that ADR-0008 must decide; Path 3 is a dependency question) —
 *    both are recorded with the component costs measured in this class.
 */
@RunWith(AndroidJUnit4::class)
class ProotGitSpikeDeviceTest {
    private lateinit var context: Context
    private lateinit var runner: ProotJobRunner
    private val workDirs = mutableListOf<File>()
    private var jobSeq = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ensureActiveRuntime()
        runner = ProotJobRunner.get(context)
    }

    @After
    fun tearDown() {
        workDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun ensureActiveRuntime() {
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        RootFsInstaller.currentActive(root)?.let { return }
        val outcome =
            RootFsInstaller.install(
                ProotRuntimeInstaller.buildInstallRequest(
                    context,
                    ProotRuntimeInstaller.loadEmbeddedLock(context),
                    ProotNative.pageSizeBytes(),
                    System.currentTimeMillis(),
                ),
            )
        assertTrue("runtime install failed: $outcome", outcome is InstallOutcome.Success)
    }

    private fun scratch(name: String): File {
        val dir = File(context.cacheDir, "git-spike-$name-${System.nanoTime()}").apply { mkdirs() }
        workDirs += dir
        return dir
    }

    private fun nextJobId(): String {
        // `job_` + 12 lowercase hex (the protocol form, ProotJobRecordCodec):
        val mixed = (++jobSeq).toULong() xor (System.nanoTime().toULong() shl 13)
        return "job_" + mixed.toString(16).padStart(12, '0').takeLast(12)
    }

    private fun nextExecutionId(): String {
        val mixed = (System.nanoTime().toULong() xor (jobSeq.toULong() shl 40))
        return "exec-git-" + mixed.toString(16).takeLast(16)
    }

    /** Seeds a working tree (no real object DB — the guest's git builds history). */
    private fun seedRepo(
        root: File,
        fileCount: Int,
        fileBytes: Int,
    ): File {
        val repo = File(root, "repo").apply { mkdirs() }
        for (i in 0 until fileCount) {
            val sub = File(repo, "src/${i % 8}").apply { mkdirs() }
            val payload = ByteArray(fileBytes) { (it % 251).toByte() }
            File(sub, "file-$i.bin").writeBytes(payload)
        }
        return repo
    }

    /**
     * Real JobZipWriter archive of the [repoName] subtree of [source] (so the
     * guest sees `/workspace/<repoName>/...`) + the manifest sha the runner holds.
     */
    @Suppress("LongMethod")
    private fun archiveTree(
        source: File,
        repoName: String,
        work: File,
    ): Pair<File, String> {
        val subtree = File(source, repoName)
        val files =
            subtree
                .walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(source).path }
                .toList()
        val entries =
            files.map { f ->
                val bytes = f.readBytes()
                JobManifestEntry(
                    f.relativeTo(source).path,
                    sha256Of(bytes),
                    bytes.size.toLong(),
                )
            }
        val document = JobManifestCodec.encode(JobManifest(entries))
        val archive = File(work, "input.zip")
        JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(document)
            entries.forEach { entry -> writer.writeEntry(entry.path, File(source, entry.path)) }
        }
        return archive to sha256Of(document.toByteArray())
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Runs one guest job over [shellCommand] with the tree snapshot; returns (record, stdout, metrics). */
    @Suppress("LongMethod")
    private fun runRepoJob(
        work: File,
        source: File,
        shellCommand: String,
        deadlineMs: Long = 300_000L,
    ): Triple<ProotJobRecord, String, Map<String, Long>> {
        val (archive, manifestSha) = archiveTree(source.parentFile!!, source.name, work)
        val archiveInMs = System.currentTimeMillis()
        val spec =
            ProotJobSpec(
                executionId = nextExecutionId(),
                jobId = nextJobId(),
                command = ProotJobCommand.Argv(listOf("/bin/sh", "-c", shellCommand)),
                relativeWorkingDirectory = "",
                environment =
                    mapOf(
                        "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
                        "HOME" to "/root",
                        "GIT_CONFIG_GLOBAL" to "/dev/null",
                        "GIT_CONFIG_SYSTEM" to "/dev/null",
                        "GIT_TERMINAL_PROMPT" to "0",
                        "GIT_PAGER" to "cat",
                    ),
                deadlineMs = deadlineMs,
                maxOutputBytes = 8L * 1024L * 1024L,
                inputManifestSha256 = manifestSha,
            )
        val inputPfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        val outputFile = File(work, "output.zip")
        val outputPfd =
            ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        val result = runner.submit(spec, inputPfd, outputPfd)
        assertTrue("submit must be accepted, got: $result", result is ProotJobSubmitResult.Accepted)
        var diag = ""
        var record: ProotJobRecord? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < deadlineMs + 60_000L) {
            val r = runner.query(spec.jobId) ?: error("job disappeared: ${spec.jobId}")
            if (r.state.isTerminal) {
                record = r
                break
            }
            Thread.sleep(250L)
        }
        assertNotNull("job ${spec.jobId} did not reach a terminal state", record)
        val jobMs = System.currentTimeMillis() - start
        val extracted = File(work, "extracted").apply { mkdirs() }
        var stdout = ""
        var outBytes = 0L
        var outEntries = 0L
        if (record!!.state != ProotJobState.SUCCEEDED) {
            // Diagnose the failure from the job's own output (the failure note is
            // not on the record; the guest's stderr is in the output archive when
            // one was produced):
            runCatching {
                ZipJobExtractor.extract(outputFile, extracted)
                stdout = File(extracted, "stdout.txt").readText()
                diag = File(extracted, "stderr.txt").readText().take(600)
            }
            error(
                "job ${spec.jobId} state=${record.state} exit=${record.exitCode} " +
                    "stdout=[$stdout] stderr=[$diag]",
            )
        }
        if (true && record.state == ProotJobState.SUCCEEDED) {
            ZipJobExtractor.extract(outputFile, extracted)
            stdout = runCatching { File(extracted, "stdout.txt").readText() }.getOrDefault("")
            outBytes = outputFile.length()
            outEntries = extracted.walkTopDown().count { it.isFile }.toLong()
        }
        return Triple(
            record!!,
            stdout,
            mapOf(
                "archiveInBytes" to archive.length(),
                "archiveInMs" to (start - archiveInMs),
                "jobMs" to jobMs,
                "archiveOutBytes" to outBytes,
                "archiveOutEntries" to outEntries,
            ),
        )
    }

    // ------------------------------------------------------------------
    // ADR-0008: sizes/times with small & medium repositories
    // ------------------------------------------------------------------

    @Test
    fun smallRepoRoundTripThroughTheRealJobPipeline() {
        val work = scratch("small")
        val repo = seedRepo(work, fileCount = 64, fileBytes = 8 * 1024)
        val repoBytes = repo.walkTopDown().sumOf { it.length() }
        val (record, stdout, m) =
            runRepoJob(
                work,
                repo,
                "cd /workspace/repo && git init -q -b main && git status --short | head -3 && " +
                    "git --version && echo GITFILES=`find .git -type f | wc -l` && echo GITKB=`du -sk .git | cut -f1`",
            )
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        val extracted = File(work, "extracted")
        assertTrue(
            "the guest `git init` must have created its own .git in the snapshot",
            File(extracted, "repo/.git").isDirectory,
        )
        writeSpike(
            "small",
            "SPIKE small: repoBytes=$repoBytes in=${m["archiveInBytes"]}B/${m["archiveInMs"]}ms " +
                "job=${m["jobMs"]}ms out=${m["archiveOutBytes"]}B/${m["archiveOutEntries"]}f stdout=[$stdout]",
        )
    }

    @Test
    fun mediumRepoCommitAndGcArchiveBudget() {
        val work = scratch("medium")
        val repo = seedRepo(work, fileCount = 512, fileBytes = 32 * 1024)
        val repoBytes = repo.walkTopDown().sumOf { it.length() }
        val (record, stdout, m) =
            runRepoJob(
                work,
                repo,
                "cd /workspace/repo && git init -q -b main && " +
                    "git -c user.email=spike@helix.local -c user.name=spike add -A && " +
                    "git -c user.email=spike@helix.local -c user.name=spike commit -q -m spike && " +
                    "git gc -q && " +
                    "echo LOOSE_OR_PACKED=$(find .git/objects -name '*.pack' | wc -l)packs " +
                    "GITFILES=$(find .git -type f | wc -l) GITKB=$(du -sk .git | cut -f1)",
            )
        assertEquals("medium repo commit+gc must succeed", ProotJobState.SUCCEEDED, record.state)
        val extracted = File(work, "extracted")
        val guestRepoBytes =
            File(extracted, "repo").walkTopDown().sumOf { it.length() }
        writeSpike(
            "medium",
            "SPIKE medium: repoBytes=$repoBytes in=${m["archiveInBytes"]}B/${m["archiveInMs"]}ms " +
                "job=${m["jobMs"]}ms out=${m["archiveOutBytes"]}B/${m["archiveOutEntries"]}f " +
                "guestRepoBytes=$guestRepoBytes stdout=[$stdout]",
        )
        assertTrue("the guest must report packed objects", "1packs" in stdout)
        assertTrue("the guest must report the .git size", stdout.contains("GITKB="))
    }

    // ------------------------------------------------------------------
    // Corruption recovery (never silently clean)
    // ------------------------------------------------------------------

    @Test
    fun aCorruptedRepoIsStablyReportedByFsck() {
        val work = scratch("corrupt")
        val repo = seedRepo(work, fileCount = 16, fileBytes = 1024)
        val (record, stdout, _) =
            runRepoJob(
                work,
                repo,
                "cd /workspace/repo && git init -q -b main; echo INIT_OK=$?; " +
                    "git -c user.email=s@h -c user.name=s add -A; echo ADD_OK=$?; " +
                    "git -c user.email=s@h -c user.name=s commit -q -m one; echo COMMIT_OK=$?; " +
                    "OBJ=`git rev-parse 'HEAD^{tree}' 2>&1`; echo OBJ=\$OBJ; " +
                    "A=`echo \$OBJ | cut -c1-2`; B=`echo \$OBJ | cut -c3`; " +
                    "printf not-a-zlib-object > \".git/objects/\$A/\$B\"; echo WRITE_OK=\$?; " +
                    "git fsck --full > /workspace/fsck.txt 2>&1; echo FSCK_EXIT=$?; " +
                    "head -4 /workspace/fsck.txt",
            )
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        assertTrue("init: $stdout", "INIT_OK=0" in stdout)
        assertTrue("add: $stdout", "ADD_OK=0" in stdout)
        assertTrue("commit: $stdout", "COMMIT_OK=0" in stdout)
        assertTrue("the corrupt write must land: $stdout", "WRITE_OK=0" in stdout)
        // The damage is REPORTED by git (device-verified: "bad sha1 file" for a
        // damaged loose object) — never silently clean. NOTE recorded in ADR-0008:
        // `git fsck` can exit 0 while REPORTING corruption, so the structured
        // executor must parse the fsck output, not just the exit code.
        assertTrue(
            "fsck must name the damage (never silently clean), got: $stdout",
            "bad sha1 file" in stdout || "missing" in stdout || "corrupt" in stdout.lowercase() ||
                "hash mismatch" in stdout,
        )
        writeSpike("corrupt", "SPIKE corrupt: fsck output=[$stdout]")
    }

    // ------------------------------------------------------------------
    // Reconciliation: one stable terminal record per job id (no replay)
    // ------------------------------------------------------------------

    @Test
    fun jobIdResolvesToExactlyOneStableTerminalRecord() {
        val work = scratch("reconcile")
        val repo = seedRepo(work, fileCount = 8, fileBytes = 512)
        val (record, _, m) =
            runRepoJob(
                work,
                repo,
                "cd /workspace/repo && git init -q -b main && git status --short | head -2 && echo RECONCILE_OK",
            )
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        val again = runner.query(record.jobId)
        assertEquals(record.jobId, again?.jobId)
        assertEquals("the record must be STABLE across queries (no replay)", record.state, again?.state)
        // The output boundary is all-or-nothing: a SUCCEEDED job always has a
        // complete, extractable output archive (there is no half-extracted tree).
        assertTrue("a SUCCEEDED job must have a complete output archive", m["archiveOutBytes"]!! > 0L)
    }

    // ------------------------------------------------------------------
    // Attack fixtures: hooks/filter/alias/submodule/credential evidence
    // ------------------------------------------------------------------

    @Test
    @Suppress("LongMethod")
    fun unguardedCommitRunsHostileHookAndAddRunsHostileFilter() {
        // EVIDENCE (why the structured executor needs guards): a snapshot can
        // carry hostile hooks/alias/filter/submodule/credential config that the
        // first-version commands trigger WITHOUT any guard.
        val work = scratch("unguarded")
        val repo = seedRepo(work, fileCount = 4, fileBytes = 256)
        // Hostile repo config carried in the snapshot (the guest `git init`
        // preserves an existing .git/config — only missing defaults are added):
        File(repo, ".git").apply { mkdirs() }
        File(repo, ".git/config").writeText(
            """
            [core]
                 repositoryformatversion = 0
                 filemode = true
                 bare = false
                 hooksPath = .git/hooks
            [alias]
                 status = !sh -c 'touch /workspace/ALIAS-RAN'
            [filter "evil"]
                 clean = touch /workspace/FILTER-RAN
                 smudge = touch /workspace/FILTER-RAN
            [submodule "evil"]
                 url = file:///tmp/never-cloned
                 path = sub
            [credential]
                 helper = touch /workspace/CRED-RAN
            """.trimIndent(),
        )
        File(repo, "filtered.txt").writeText("trigger\n")
        File(repo, ".gitattributes").writeText("*.txt filter=evil\n")
        val (record, stdout, _) =
            runRepoJob(
                work,
                repo,
                "cd /workspace/repo && git init -q -b main 2>/dev/null; echo INIT_OK=$?; " +
                    "mkdir -p .git/hooks && " +
                    "printf '#!/bin/sh\ntouch /workspace/HOOK-RAN\n' > .git/hooks/post-commit && " +
                    "chmod +x .git/hooks/post-commit; " +
                    "git status --short >/dev/null 2>&1; echo STATUS_OK=$?; " +
                    "git add -A >/dev/null 2>&1; echo ADD_OK=$?; " +
                    "git -c user.email=s@h -c user.name=s commit -q -m spike >/dev/null 2>&1; echo COMMIT_OK=$?; " +
                    "git config filter.evil.clean; git check-attr filter -- filtered.txt; " +
                    "test -e /workspace/HOOK-RAN && echo HOOK_RAN || echo HOOK_INERT; " +
                    "test -e /workspace/FILTER-RAN && echo FILTER_RAN || echo FILTER_INERT; " +
                    "test -e /workspace/ALIAS-RAN && echo ALIAS_RAN || echo ALIAS_INERT; " +
                    "test -e /workspace/CRED-RAN && echo CRED_RAN || echo CRED_INERT",
            )
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        assertTrue("git init: $stdout", "INIT_OK=0" in stdout)
        assertTrue("status: $stdout", "STATUS_OK=0" in stdout)
        assertTrue("add: $stdout", "ADD_OK=0" in stdout)
        assertTrue("commit: $stdout", "COMMIT_OK=0" in stdout)
        // EVIDENCE: without guards, the hostile hook (commit) and filter (add)
        // DO execute inside the guest and write into the job workspace:
        assertTrue("the hostile post-commit hook must have run (evidence): $stdout", "HOOK_RAN" in stdout)
        assertTrue("the hostile clean filter must have run (evidence): $stdout", "FILTER_RAN" in stdout)
        // Inert by construction: no alias expansion for structured subcommands,
        // no credential helper (no remote commands in v1):
        assertTrue("the alias must NOT have run: $stdout", "ALIAS_INERT" in stdout)
        assertTrue("no credential helper may have run: $stdout", "CRED_INERT" in stdout)
        writeSpike("unguarded", "SPIKE unguarded: $stdout")
    }

    @Test
    @Suppress("LongMethod")
    fun guardedCommitIsHookSafeAndFirstVersionSetSucceeds() {
        // With the executor's mandatory per-invocation hook guard
        // (`-c core.hooksPath=/dev/null`), the same hostile repo cannot fire
        // hooks; the full first-version candidate set still succeeds. The clean
        // FILTER still runs (a hooksPath guard cannot neutralize it) — that is
        // the evidence for the host-side repo pre-scan requirement recorded in
        // ADR-0008 (refuse add/commit on a repo defining filter/diff/merge
        // command config).
        val work = scratch("guarded")
        val repo = seedRepo(work, fileCount = 4, fileBytes = 256)
        File(repo, ".git").apply { mkdirs() }
        File(repo, ".git/config").writeText(
            """
            [core]
                 repositoryformatversion = 0
                 filemode = true
                 bare = false
                 hooksPath = .git/hooks
            [filter "evil"]
                 clean = touch /workspace/FILTER-RAN
                 smudge = touch /workspace/FILTER-RAN
            """.trimIndent(),
        )
        File(repo, "filtered.txt").writeText("trigger\n")
        File(repo, ".gitattributes").writeText("*.txt filter=evil\n")
        val (record, stdout, _) =
            runRepoJob(
                work,
                repo,
                "cd /workspace/repo && git init -q -b main 2>/dev/null; " +
                    "mkdir -p .git/hooks && " +
                    "printf '#!/bin/sh\ntouch /workspace/HOOK-RAN\n' > .git/hooks/post-commit && " +
                    "chmod +x .git/hooks/post-commit; " +
                    "git -c core.hooksPath=/dev/null status --short >/dev/null 2>&1; " +
                    "echo STATUS_OK=$?; " +
                    "git -c core.hooksPath=/dev/null add -A >/dev/null 2>&1; echo ADD_OK=$?; " +
                    "git -c core.hooksPath=/dev/null -c user.email=s@h -c user.name=s commit -q -m " +
                    "spike >/dev/null 2>&1; echo COMMIT_OK=$?; " +
                    "git -c core.hooksPath=/dev/null diff --stat | head -1 >/dev/null; echo DIFF_OK=$?; " +
                    "git -c core.hooksPath=/dev/null log --oneline | head -1 >/dev/null; echo LOG_OK=$?; " +
                    "test -e /workspace/HOOK-RAN && echo HOOK_RAN || echo HOOK_INERT; " +
                    "test -e /workspace/FILTER-RAN && echo FILTER_RAN || echo FILTER_INERT",
            )
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        assertTrue("guarded status: $stdout", "STATUS_OK=0" in stdout)
        assertTrue("guarded add: $stdout", "ADD_OK=0" in stdout)
        assertTrue("guarded commit: $stdout", "COMMIT_OK=0" in stdout)
        assertTrue("guarded diff: $stdout", "DIFF_OK=0" in stdout)
        assertTrue("guarded log: $stdout", "LOG_OK=0" in stdout)
        assertTrue("the hook must be inert under the guard: $stdout", "HOOK_INERT" in stdout)
        // EVIDENCE (the pre-scan requirement): a hooksPath guard cannot
        // neutralize attribute filters — the hostile filter STILL ran on add
        // (and emptied the file content: a silent data-alteration channel into
        // the snapshot):
        assertTrue("the hostile filter must still run under the hook guard (evidence): $stdout", "FILTER_RAN" in stdout)
        writeSpike("guarded", "SPIKE guarded (filter evidence): $stdout")
    }

    private fun writeSpike(
        tag: String,
        text: String,
    ) {
        println("SPIKE $tag: $text")
        // /data/local/tmp is NOT writable by an app uid; use the companion's
        // own external-files fallback: write to the app cache (readable via
        // run-as for a debug build) and copy via adb if needed.
        runCatching {
            File(context.cacheDir, "git-spike-$tag.txt").writeText(text)
        }
    }
}
