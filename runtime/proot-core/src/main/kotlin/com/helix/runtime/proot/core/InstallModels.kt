package com.helix.runtime.proot.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Facts the installer needs from the device but does not measure itself. */
data class InstallFacts(
    /** Device page size (4096 or 16384) driving the ELF `PT_LOAD` pre-activation check. */
    val pageSizeBytes: Long,
    val nowEpochMs: Long,
    val installIdGenerator: () -> String = RootFsInstaller::newInstallId,
)

/** One PRoot-side binary to place under `bin/` of the new version directory. */
data class ProotAssetSource(
    /** Destination path relative to `bin/`, e.g. `proot`, `loader`, `lib/libtalloc.so.2`. */
    val destPath: String,
    val stream: () -> InputStream,
)

/** Everything [RootFsInstaller.install] needs; the lock must already pass [RuntimeBaseline.requireBaseline]. */
data class InstallRequest(
    val runtimeRoot: File,
    val lock: RuntimeLock,
    /** The embedded raw tar stream (already gunzipped — see the AGP `.gz`-strip rule). */
    val rootfsArchiveStream: () -> InputStream,
    val prootAssets: List<ProotAssetSource>,
    val facts: InstallFacts,
)

/** Outcome of [RootFsInstaller.install]. */
sealed interface InstallOutcome {
    /** New version promoted and activated atomically; the old active is now the rollback. */
    data class Success(
        val installId: String,
        val rollbackId: String?,
        val swept: List<String>,
        val smoke: RuntimeSmokeResult,
        val members: Int,
        val extractedBytes: Long,
    ) : InstallOutcome

    /** Failed at [stage] with a stable, user-safe [reason]; prior state untouched. */
    data class Failure(
        val stage: String,
        val reason: String,
    ) : InstallOutcome
}

/** Outcome of [RootFsInstaller.activateRollback]. */
sealed interface RollbackOutcome {
    data class Done(
        val newActiveId: String,
        val newRollbackId: String,
    ) : RollbackOutcome

    data object NoActive : RollbackOutcome

    data object NoRollback : RollbackOutcome

    data class Failed(
        val reason: String,
    ) : RollbackOutcome
}

/** Thrown when the post-install smoke run reports any failure. */
class InstallSmokeFailed(
    message: String,
) : IOException(message)

/** Streams [source] while feeding [digest] and counting bytes (for the hash re-verify). */
internal class TeeDigestStream(
    private val source: InputStream,
    private val digest: MessageDigest,
) : InputStream() {
    var bytesRead = 0L
        private set

    override fun read(): Int {
        val b = source.read()
        if (b >= 0) {
            digest.update(b.toByte())
            bytesRead++
        }
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = source.read(b, off, len)
        if (n > 0) {
            digest.update(b, off, n)
            bytesRead += n
        }
        return n
    }
}
