package com.helix.runtime.proot.app

import android.content.Context
import android.os.Build
import com.helix.runtime.proot.core.InstallFacts
import com.helix.runtime.proot.core.InstallRequest
import com.helix.runtime.proot.core.ProotAssetSource
import com.helix.runtime.proot.core.RootFsCompression
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeAbi
import com.helix.runtime.proot.core.RuntimeLock
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.requireBaseline
import java.io.File

/**
 * Android adapter for [RootFsInstaller] (HXA-082). Resolves the embedded runtime
 * assets (lock, XZ-encoded tar, PRoot binaries) from the APK assets, the
 * `filesDir/runtime` state root (architecture doc local-code-execution section
 * 6.3), and the platform precondition (device ABI).
 *
 * The installer itself is pure JVM (`:runtime:proot-core`) and fully unit-tested
 * off-device; this adapter only answers "where are the bytes and where does the
 * state live" for a concrete Android context.
 */
object ProotRuntimeInstaller {
    /** All runtime state lives under `filesDir/runtime` (version dirs + `state/`). */
    fun runtimeRoot(context: Context): File = File(context.filesDir, "runtime")

    /** Parses the embedded lock, enforces the HXA-080 baseline and the device ABI. */
    fun loadEmbeddedLock(context: Context): RuntimeLock {
        val lock =
            RuntimeLockCodec.parse(
                context.assets
                    .open("runtime/runtime-lock.json")
                    .bufferedReader()
                    .use { it.readText() },
            )
        lock.requireBaseline()
        requireDeviceAbi(lock.abi)
        return lock
    }

    /**
     * Builds the [InstallRequest] over the embedded assets. The archive stream is
     * the decoded raw tar — the lock continues to pin exactly these bytes.
     * The XZ input, output and decoder memory are bounded. Nothing is read
     * until [RootFsInstaller.install] runs.
     */
    fun buildInstallRequest(
        context: Context,
        lock: RuntimeLock,
        pageSizeBytes: Long,
        nowEpochMs: Long,
    ): InstallRequest {
        val embeddedName = embeddedArchiveName(lock)
        return InstallRequest(
            runtimeRoot = runtimeRoot(context),
            lock = lock,
            rootfsArchiveStream = {
                RootFsCompression.open(
                    { context.assets.open("runtime/rootfs/$embeddedName") },
                    requireNotNull(lock.component("alpine-rootfs")).size,
                )
            },
            prootAssets =
                listOf(
                    ProotAssetSource("proot", { context.assets.open("runtime/proot/proot") }),
                    ProotAssetSource("loader", { context.assets.open("runtime/proot/loader") }),
                    ProotAssetSource("lib/libtalloc.so.2", { context.assets.open("runtime/proot/lib/libtalloc.so.2") }),
                    ProotAssetSource("lib/libandroid-shmem.so", {
                        context.assets.open("runtime/proot/lib/libandroid-shmem.so")
                    }),
                ),
            facts = InstallFacts(pageSizeBytes = pageSizeBytes, nowEpochMs = nowEpochMs),
        )
    }

    /**
     * The source lock names raw tar bytes. The APK carries one XZ encoding of them.
     */
    fun embeddedArchiveName(lock: RuntimeLock): String {
        val rootfs =
            lock.component("alpine-rootfs") ?: error("lock has no alpine-rootfs component")
        val name = rootfs.url.substringAfterLast('/').substringBefore('?')
        return name.removeSuffix(".gz") + ".xz"
    }

    private fun requireDeviceAbi(abi: RuntimeAbi) {
        val supported = Build.SUPPORTED_64_BIT_ABIS
        require(abi.wire in supported) {
            "device ABIs $supported do not include the runtime ABI ${abi.wire}"
        }
    }
}
