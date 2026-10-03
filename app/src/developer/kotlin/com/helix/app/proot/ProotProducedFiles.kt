package com.helix.app.proot

import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.PathResolution
import com.helix.core.workspace.WorkspaceQuota
import com.helix.core.workspace.WorkspaceQuotaPolicy
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobPath
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Publishes only the explicit guest output/ convention from an already verified original Job archive. */
internal object ProotProducedFiles {
    data class Output(
        val relative: String,
        val file: File,
        val size: Long,
        val sha256: String,
        val mime: String,
    )

    fun relativeDirectory(jobId: String): String {
        ProotJobRecordCodec.checkJobId(jobId)
        return "output/jobs/$jobId"
    }

    fun publish(
        workspace: File,
        extracted: File,
        jobId: String,
        entries: List<JobManifestEntry>,
        beforePublish: () -> Unit = {},
        register: (Output) -> Unit,
    ) {
        val requestedRoot = workspace.toPath().toAbsolutePath().normalize()
        // Android's private directory can have an ancestor alias (/data/user/0).
        // Keep every subsequent containment check under the same verified canonical root.
        val root = PathResolution.resolveWithinRoot(requestedRoot, requestedRoot)
        val verifiedRoot = extracted.toPath().toAbsolutePath().normalize()
        val base = relativeDirectory(jobId)
        entries.filter { it.path.startsWith("output/") }.forEach { entry ->
            JobPath.validate(entry.path)
            require(entry.size in 0..JobArchiveLimits.MAX_SINGLE_FILE_BYTES)
            val suffix = entry.path.removePrefix("output/")
            JobPath.validate(suffix)
            val source = PathResolution.resolveWithinRoot(verifiedRoot, verifiedRoot.resolve(entry.path))
            check(matches(source, entry)) { "Verified Job file changed before publication" }
            val relative = "$base/$suffix"
            val target = PathResolution.resolveWithinRoot(root, root.resolve(relative))
            beforePublish()
            Files.createDirectories(target.parent)
            PathResolution.resolveWithinRoot(root, target)
            if (!Files.exists(target)) {
                WorkspaceQuota.ensureRoom(root, entry.size, WorkspaceQuotaPolicy.default.maxWorkspaceBytes)
                publishNew(source, target, entry, beforePublish)
            }
            check(matches(target, entry)) { "Job output already exists with different content; nothing overwritten" }
            val probe = ContentProbe.probe(target)
            beforePublish()
            register(Output(relative, target.toFile(), entry.size, entry.sha256, probe.mimeType))
        }
    }

    private fun publishNew(
        source: Path,
        target: Path,
        entry: JobManifestEntry,
        beforePublish: () -> Unit,
    ) {
        val temporary = Files.createTempFile(target.parent, ".helix-tmp-", ".job")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val out = Channels.newOutputStream(channel)
                copy(source, out, entry.size)
                channel.force(true)
            }
            check(matches(temporary, entry)) { "Job copy does not match the verified manifest" }
            try {
                // Android SELinux forbids hard links in app data. Reserve the final name
                // with CREATE_NEW so a raced user file is never replaced. Register only
                // after the verified bytes are flushed; interrupted copies remain unregistered
                // and fail the digest check on retry instead of overwriting uncertain data.
                beforePublish()
                FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    copy(temporary, Channels.newOutputStream(channel), entry.size)
                    channel.force(true)
                }
            } catch (_: FileAlreadyExistsException) {
                check(matches(target, entry)) { "Concurrent Job output differs; nothing overwritten" }
            }
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: IOException) {
                // Only an owned temporary may remain; do not remove the published output.
            }
        }
    }

    private fun copy(
        source: Path,
        output: java.io.OutputStream,
        maxBytes: Long,
    ) {
        Files.newInputStream(source).use { input ->
            val buffer = ByteArray(65536)
            var bytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0 && bytes + count <= maxBytes) { "Job file changed while copying" }
                output.write(buffer, 0, count)
                bytes += count
            }
        }
    }

    private fun boundedHash(
        path: Path,
        size: Long,
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || total + count > size) return null
                total += count
                digest.update(buffer, 0, count)
            }
        }
        return if (total == size) digest.digest().joinToString("") { "%02x".format(it) } else null
    }

    private fun matches(
        path: Path,
        entry: JobManifestEntry,
    ): Boolean =
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) != entry.size) {
            false
        } else {
            boundedHash(path, entry.size) == entry.sha256
        }
}
