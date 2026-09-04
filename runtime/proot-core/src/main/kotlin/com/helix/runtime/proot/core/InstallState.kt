package com.helix.runtime.proot.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * Internal state-file machinery for [RootFsInstaller]: the exclusive install lock,
 * the activation pointers (`state/active.json` / `state/rollback.json`), orphan
 * sweeping and atomic file writes. Kept separate so the public installer surface
 * stays small and every state mutation lives in one place.
 */
internal object InstallState {
    const val PARTIAL_PREFIX = ".partial-"

    /**
     * Acquires the exclusive install lock, or null when another operation holds it.
     * A same-process overlapping lock (double-triggered install) is reported as
     * "held", never as a crash: the exception carries no diagnostic beyond "held"
     * and the null return IS the signal, so swallowing it is deliberate.
     */
    @Suppress("SwallowedException")
    fun acquireStateLock(root: File): FileChannel? {
        val state = File(root, "state")
        if (!state.mkdirs() && !state.isDirectory) throw IOException("cannot create state directory")
        val channel =
            FileChannel.open(File(state, "install.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        return try {
            if (channel.tryLock() == null) {
                channel.close()
                null
            } else {
                channel
            }
        } catch (e: OverlappingFileLockException) {
            channel.close()
            null
        }
    }

    /** Reads one activation pointer; null when the file is absent, throws when corrupt. */
    fun readActivation(
        root: File,
        name: String,
    ): RuntimeActivation? {
        val file = File(File(root, "state"), name)
        if (!file.exists()) return null
        return RuntimeManifestCodec.parseActivation(file.readText())
    }

    /** Atomically writes one activation pointer (tmp + fsync + rename + dir fsync). */
    fun writeActivation(
        root: File,
        name: String,
        activation: RuntimeActivation,
    ) {
        atomicWrite(File(File(root, "state"), name), RuntimeManifestCodec.encodeActivation(activation))
    }

    /**
     * Deletes stale `.partial-*` trees and version directories not referenced by
     * [activeId]/[rollbackId]. Returns the deleted names. This is what makes every
     * crash point self-healing on the next operation.
     */
    fun sweepOrphans(
        root: File,
        activeId: String?,
        rollbackId: String?,
    ): List<String> {
        val deleted = mutableListOf<String>()
        for (child in root.listFiles().orEmpty().sortedBy { it.name }) {
            if (!child.isDirectory) continue
            val stalePartial = child.name.startsWith(PARTIAL_PREFIX)
            val orphanVersion =
                child.name.startsWith("inst_") && child.name != activeId && child.name != rollbackId
            if (stalePartial || orphanVersion) {
                deleteRecursivelyQuietly(child)
                deleted += child.name
            }
        }
        return deleted
    }

    /**
     * Deletes the previous rollback's directory (now unreferenced) — called only
     * after BOTH activation files have been rewritten.
     */
    fun deleteOldRollback(
        root: File,
        previousRollback: RuntimeActivation?,
    ) {
        if (previousRollback == null) return
        val dir = File(root, previousRollback.installId)
        if (dir.isDirectory) deleteRecursivelyQuietly(dir)
    }

    fun deleteRecursivelyQuietly(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    /** Atomic text write: same-directory tmp, fsync, rename, directory fsync. */
    fun atomicWrite(
        target: File,
        content: String,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp-" + randomHex(6))
        FileOutputStream(tmp).use { out ->
            out.write(content.encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            runCatching { tmp.delete() }
            throw IOException("atomic rename failed: ${target.name}")
        }
        // Best-effort directory fsync so the rename itself is durable.
        val parent = target.parentFile
        if (parent != null) {
            runCatching { FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(false) } }
        }
    }

    fun randomHex(nBytes: Int): String {
        val bytes = ByteArray(nBytes)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    /**
     * Writes the new activation pair. The ACTIVE pointer is written first; the
     * rollback pointer second; the old rollback DIRECTORY is deleted only after
     * both (by the caller) — so a crash between any two steps never leaves a
     * pointer at a missing directory.
     */
    fun writeActivations(
        root: File,
        active: RuntimeActivation?,
        installId: String,
        nowEpochMs: Long,
    ): String? {
        writeActivation(root, "active.json", RuntimeActivation(1, installId, nowEpochMs))
        if (active == null) return null
        writeActivation(root, "rollback.json", RuntimeActivation(1, active.installId, nowEpochMs))
        return active.installId
    }

    /**
     * Swaps active and rollback atomically (HXA-087 consumes this via
     * [RootFsInstaller.activateRollback]). Validates that both referenced version
     * directories and the rollback's manifest exist before touching any pointer.
     * No files move.
     */
    fun swapActivations(
        root: File,
        nowEpochMs: Long,
    ): RollbackOutcome {
        val active = readActivation(root, "active.json")
        val rollback = readActivation(root, "rollback.json")
        // Validate everything BEFORE writing; the happy branch performs the swap and
        // parses the rollback manifest as a final integrity gate.
        return when {
            active == null -> {
                RollbackOutcome.NoActive
            }

            rollback == null -> {
                RollbackOutcome.NoRollback
            }

            !File(root, rollback.installId).isDirectory -> {
                RollbackOutcome.Failed("rollback version directory missing")
            }

            !File(root, active.installId).isDirectory -> {
                RollbackOutcome.Failed("active version directory missing")
            }

            else -> {
                RuntimeManifestCodec.parseManifest(
                    File(File(root, rollback.installId), "manifest.json").readText(),
                )
                writeActivation(root, "active.json", RuntimeActivation(1, rollback.installId, nowEpochMs))
                writeActivation(root, "rollback.json", RuntimeActivation(1, active.installId, nowEpochMs))
                RollbackOutcome.Done(rollback.installId, active.installId)
            }
        }
    }
}
