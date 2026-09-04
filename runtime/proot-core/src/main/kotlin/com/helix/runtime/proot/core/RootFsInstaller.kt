package com.helix.runtime.proot.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The device-side RootFS installer (HXA-082; architecture doc local-code-execution
 * section 6.4 steps 5-8: 解压到 `.partial` 并再次验证 SHA-256、防 Zip Slip、写入新
 * 版本目录并运行 smoke、原子切换 `active.json` 并保留一版 rollback).
 *
 * Pure JVM over `java.io.File` so the entire state machine is testable off-device;
 * the Android glue (asset streams, `filesDir/runtime`) is a thin adapter in
 * `:runtime:proot-app`.
 *
 * Directory model under [InstallRequest.runtimeRoot] (`filesDir/runtime`):
 * ```
 * runtime/
 * ├── state/
 * │   ├── install.lock     # exclusive FileChannel lock, held for the whole operation
 * │   ├── active.json      # RuntimeActivation -> the active install id
 * │   └── rollback.json    # RuntimeActivation -> the one kept rollback install id
 * ├── inst_<12hex>/        # one directory per installed version
 * │   ├── manifest.json
 * │   ├── bin/             # proot, loader, lib/...
 * │   ├── rootfs/          # extracted Alpine tree
 * │   ├── home/
 * │   └── tmp/
 * └── .partial-<rand>/     # in-flight install (swept on every start; deleted on failure)
 * ```
 * `state/` is deliberately version-independent: the activation pointers must have a
 * stable path across versions for the atomic switch to mean anything.
 *
 * Crash safety: a version directory is promoted (renamed) from `.partial` into
 * `runtime/` only after hash verification + extraction + smoke all pass; the
 * activation files are then written with write-tmp+fsync+rename. Every crash point
 * leaves either the previous active version intact (plus sweepable orphans) or a
 * consistent active+rollback pair — a stale rollback pointer always names a
 * directory that still exists, because the old rollback directory is deleted only
 * after BOTH activation files are rewritten.
 */
private const val MAX_BINARY_BYTES = 64L * 1024L * 1024L

object RootFsInstaller {
    /**
     * Installs a new version and activates it atomically. Never deletes the current
     * active version; on any failure the previous state is untouched and the
     * `.partial` tree is removed. The state lock is released in all outcomes.
     */
    fun install(request: InstallRequest): InstallOutcome {
        val channel =
            InstallState.acquireStateLock(request.runtimeRoot)
                ?: return InstallOutcome.Failure("lock", "another install is in progress")
        return try {
            doInstall(request)
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Swaps active and rollback atomically (HXA-087 consumes this). No files move. */
    fun activateRollback(
        runtimeRoot: File,
        nowEpochMs: Long,
    ): RollbackOutcome {
        val channel = InstallState.acquireStateLock(runtimeRoot)
        if (channel == null) return RollbackOutcome.Failed("another install is in progress")
        try {
            return InstallState.swapActivations(runtimeRoot, nowEpochMs)
        } finally {
            runCatching { channel.close() }
        }
    }

    /** The currently active activation, or null when never installed (corrupt state throws). */
    fun currentActive(runtimeRoot: File): RuntimeActivation? = InstallState.readActivation(runtimeRoot, "active.json")

    /** The kept rollback activation, or null when there is none (corrupt state throws). */
    fun currentRollback(runtimeRoot: File): RuntimeActivation? =
        InstallState.readActivation(runtimeRoot, "rollback.json")

    fun newInstallId(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return "inst_" + bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    @Suppress("TooGenericExceptionCaught") // fail-closed stage boundary: EVERY exception
    // type here (IO, schema, codec, tar, smoke) is a legitimate install failure and
    // must map to Failure(stage) without touching the previous active version.
    private fun doInstall(request: InstallRequest): InstallOutcome {
        val root = request.runtimeRoot
        var stage = "state"
        try {
            val active = InstallState.readActivation(root, "active.json")
            val rollback = InstallState.readActivation(root, "rollback.json")
            val swept = InstallState.sweepOrphans(root, active?.installId, rollback?.installId)
            val installId = request.facts.installIdGenerator()
            RuntimeManifestCodec.checkInstallId(installId)
            val partial = File(root, InstallState.PARTIAL_PREFIX + InstallState.randomHex(8))
            val staging = File(partial, installId)
            if (!staging.mkdirs()) throw IOException("cannot create staging directory")
            try {
                stage = "archive"
                val extraction = extractAndVerifyArchive(request, File(staging, "rootfs"))
                stage = "binaries"
                placeBinaries(request, staging)
                File(staging, "home").mkdirs()
                File(staging, "tmp").mkdirs()
                stage = "smoke"
                val smoke = InstallSmoke(request.lock, request.facts).run(staging)
                if (smoke.status != SmokeStatus.PASSED) throw InstallSmokeFailed(smoke.failures.joinToString("; "))
                stage = "manifest"
                writeManifest(staging, request, installId, smoke)
                stage = "promote"
                promote(staging, root, installId, partial)
                stage = "activate"
                val newRollbackId = InstallState.writeActivations(root, active, installId, request.facts.nowEpochMs)
                InstallState.deleteOldRollback(root, rollback)
                return InstallOutcome.Success(
                    installId = installId,
                    rollbackId = newRollbackId,
                    swept = swept,
                    smoke = smoke,
                    members = extraction.members.size,
                    extractedBytes = extraction.extractedBytes,
                )
            } finally {
                InstallState.deleteRecursivelyQuietly(partial)
            }
        } catch (e: Exception) {
            return InstallOutcome.Failure(stage, e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Streams the embedded archive exactly once, verifying the SHA-256 and byte
     * count against the lock while extracting into the staging tree (section 6.4
     * step 5: 解压到 `.partial` 并再次验证 embedded archive SHA-256). A mismatch
     * (or any structural violation) aborts before promotion; the staging tree is
     * disposable.
     */
    private fun extractAndVerifyArchive(
        request: InstallRequest,
        dest: File,
    ): TarExtraction {
        val component =
            request.lock.component("alpine-rootfs")
                ?: throw IOException("lock has no alpine-rootfs component")
        val digest = MessageDigest.getInstance("SHA-256")
        val feed = TeeDigestStream(request.rootfsArchiveStream(), digest)
        val extraction = TarStream.extract(feed, dest)
        val actualSha =
            digest
                .digest()
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        // Both mismatches are the same failure class: the embedded bytes are not the
        // locked archive. Report the first violated invariant.
        val problem =
            when {
                feed.bytesRead != component.size -> {
                    "rootfs archive size ${feed.bytesRead} != lock size ${component.size}"
                }

                actualSha != component.sha256 -> {
                    "rootfs archive sha256 $actualSha != lock sha256 ${component.sha256}"
                }

                else -> {
                    null
                }
            }
        if (problem != null) throw IOException(problem)
        return extraction
    }

    private fun placeBinaries(
        request: InstallRequest,
        staging: File,
    ) {
        val bin = File(staging, "bin")
        for (asset in request.prootAssets) {
            val destPath = TarPathSafety.normalizeMemberPath(asset.destPath)
            val target = File(bin, destPath)
            if (target.exists()) throw IOException("binary destination already exists: $destPath")
            target.parentFile?.mkdirs()
            writeBinaryCapped(target, asset.stream, destPath)
            // Device-verified (HXA-084): the asset streams carry no exec bit and the
            // app-private umask leaves the files 0600 — proot/loader must be owner
            // executable or nothing can ever run. The smoke re-checks this.
            if (destPath in EXECUTABLE_ASSETS) {
                if (!target.setReadable(true, false) || !target.setExecutable(true, false)) {
                    throw IOException("cannot set exec mode on: $destPath")
                }
            }
        }
    }

    private val EXECUTABLE_ASSETS = setOf("proot", "loader")

    private fun writeManifest(
        staging: File,
        request: InstallRequest,
        installId: String,
        smoke: RuntimeSmokeResult,
    ) {
        val manifest =
            RuntimeInstallManifest(
                schemaVersion = 1,
                installId = installId,
                abi = request.lock.abi,
                installedAtEpochMs = request.facts.nowEpochMs,
                lockSha256 = RuntimeLockCodec.sha256Hex(request.lock),
                lock = request.lock,
                smoke = smoke,
            )
        InstallState.atomicWrite(File(staging, "manifest.json"), RuntimeManifestCodec.encodeManifest(manifest))
    }

    private fun promote(
        staging: File,
        root: File,
        installId: String,
        partial: File,
    ) {
        val target = File(root, installId)
        if (target.exists()) throw IOException("version directory already exists: $installId")
        if (!staging.renameTo(target)) throw IOException("cannot promote version directory: $installId")
        InstallState.deleteRecursivelyQuietly(partial)
    }
}

/** Streams one PRoot binary into [target], refusing anything over [MAX_BINARY_BYTES]. */
private fun writeBinaryCapped(
    target: File,
    stream: () -> java.io.InputStream,
    label: String,
) {
    FileOutputStream(target).use { out ->
        stream().use { src ->
            copyCapped(src, out, label)
        }
    }
}

private fun copyCapped(
    src: java.io.InputStream,
    out: java.io.OutputStream,
    label: String,
) {
    val buf = ByteArray(65536)
    var written = 0L
    while (true) {
        val n = src.read(buf)
        if (n < 0) return
        written += n
        if (written > MAX_BINARY_BYTES) throw IOException("binary too large: $label")
        out.write(buf, 0, n)
    }
}
