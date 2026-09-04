package com.helix.runtime.proot.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The post-extraction, pre-activation smoke run (HXA-082; architecture doc
 * local-code-execution section 6.4 step 7: 写入新版本目录并运行 smoke test).
 *
 * This is a STRUCTURAL smoke: it executes no native code (running python/node/git
 * under PRoot is HXA-084/086). It verifies:
 *  1. every required rootfs entry exists (default shell, busybox, the musl
 *     interpreter, and the five baseline tools at their installed paths);
 *  2. every ELF under `bin/` and `rootfs/` is structurally valid (fail-closed),
 *     aligned to at least [InstallFacts.pageSizeBytes] (the device page size —
 *     the 16 KiB pre-activation check), and matches the lock ABI's `e_machine`;
 *  3. the lock's canonical fingerprint is stable.
 *
 * The result is recorded in the version's `manifest.json` and is the ONLY thing
 * that stands between a promoted directory and the activation switch.
 */
class InstallSmoke(
    private val lock: RuntimeLock,
    private val facts: InstallFacts,
) {
    fun run(installDir: File): RuntimeSmokeResult {
        val failures = mutableListOf<String>()
        checkRequiredRootfsPaths(installDir, failures)
        checkExecBits(installDir, failures)
        checkElfs(installDir, failures)
        checkLockFingerprint(failures)
        return RuntimeSmokeResult(
            status = if (failures.isEmpty()) SmokeStatus.PASSED else SmokeStatus.FAILED,
            checkedAtEpochMs = facts.nowEpochMs,
            failures = failures,
        )
    }

    private fun checkRequiredRootfsPaths(
        installDir: File,
        failures: MutableList<String>,
    ) {
        for (path in requiredPaths(lock.abi)) {
            // NOFOLLOW_LINKS: rootfs entries are frequently symlinks (busybox
            // multiplexing, absolute interpreter links). Under PRoot they resolve
            // inside the chroot, but on the host absolute targets are dangling —
            // the structural question is "the entry exists in the tree", not
            // "the link resolves on the host".
            if (!Files.exists(File(installDir, "rootfs/$path").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                failures += "missing rootfs path: $path"
            }
        }
    }

    /**
     * The PRoot pair must be owner-executable: without it the install is
     * structurally dead (device-verified in HXA-084 — the asset streams carry no
     * mode and the installer's umask produced 0600 files that cannot exec).
     */
    private fun checkExecBits(
        installDir: File,
        failures: MutableList<String>,
    ) {
        for (name in listOf("proot", "loader")) {
            val bin = File(installDir, "bin/$name")
            if (!bin.isFile || !bin.canExecute()) {
                failures += "bin/$name missing or not executable"
            }
        }
    }

    private fun requiredPaths(abi: RuntimeAbi): List<String> {
        val interpreter =
            if (abi == RuntimeAbi.ARM64_V8A) "lib/ld-musl-aarch64.so.1" else "lib/ld-musl-x86_64.so.1"
        return listOf(
            "bin/sh",
            "bin/bash",
            "bin/busybox",
            "etc",
            interpreter,
            "usr/bin/git",
            "usr/bin/python3",
            "usr/bin/node",
            "usr/bin/rg",
        )
    }

    private fun checkElfs(
        installDir: File,
        failures: MutableList<String>,
    ) {
        val expectedMachine =
            if (lock.abi == RuntimeAbi.ARM64_V8A) {
                ElfLoaderAlignChecker.MACHINE_AARCH64
            } else {
                ElfLoaderAlignChecker.MACHINE_X86_64
            }
        for (sub in listOf("bin", "rootfs")) {
            val base = File(installDir, sub)
            if (!base.isDirectory) continue
            // Regular files only, NOT following symlinks: every in-tree ELF byte is
            // then scanned exactly once; link aliases resolve under PRoot's chroot,
            // and chasing them on the host could scan (or trip over) host files.
            for (file in base
                .walkTopDown()
                .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                .toList()
                .sortedBy { it.path }) {
                if (!isElfFile(file)) continue
                val label = "$sub/" + file.relativeTo(base).path
                checkElfFile(label, file, expectedMachine, failures)
            }
        }
    }

    private fun checkElfFile(
        label: String,
        file: File,
        expectedMachine: Long,
        failures: MutableList<String>,
    ) {
        // Bounded read: the structural check needs only the ELF header and the
        // program-header table (measured max in the pinned rootfs: 680 bytes;
        // 64 KiB is two orders of magnitude beyond that). A file whose phdrs
        // extend past the prefix is rejected as unverifiable — fail closed, and
        // the smoke's memory cost stays constant regardless of member size
        // (the largest pinned member is 51 MB; it must never be buffered whole
        // on a memory-limited device process).
        val result = ElfLoaderAlignChecker.scan(readPrefix(file, elfScanPrefixBytes))
        when {
            result.error != null -> {
                failures += "malformed ELF: $label (${result.error})"
            }

            !result.meets(facts.pageSizeBytes) -> {
                failures +=
                    "ELF below ${facts.pageSizeBytes}B page alignment: $label (min p_align=${result.minLoadAlign})"
            }

            result.machine != expectedMachine -> {
                failures += "ELF ABI mismatch: $label (e_machine=${result.machine}, expected $expectedMachine)"
            }

            else -> {
                Unit
            }
        }
    }

    private fun checkLockFingerprint(failures: MutableList<String>) {
        // Canonical stability: the lock must round-trip encode -> parse unchanged,
        // so the fingerprint recorded in the version manifest is reproducible by
        // every later reader (update, uninstall, HXA-087 legal screens).
        val reparsed = RuntimeLockCodec.parse(RuntimeLockCodec.encode(lock))
        if (RuntimeLockCodec.sha256Hex(lock) != RuntimeLockCodec.sha256Hex(reparsed)) {
            failures += "lock canonical fingerprint unstable"
        }
    }

    /** Max bytes read per ELF during the structural smoke (see [checkElfFile]). */
    private val elfScanPrefixBytes = 65536

    /** Reads at most [limit] leading bytes of [file] (fewer if the file is shorter). */
    private fun readPrefix(
        file: File,
        limit: Int,
    ): ByteArray {
        val buf = ByteArray(limit)
        file.inputStream().use { input ->
            var off = 0
            while (off < limit) {
                val n = input.read(buf, off, limit - off)
                if (n < 0) break
                off += n
            }
            return buf.copyOf(off)
        }
    }

    private fun isElfFile(file: File): Boolean {
        val magic = ByteArray(4)
        file.inputStream().use { input ->
            var off = 0
            while (off < magic.size) {
                val n = input.read(magic, off, magic.size - off)
                if (n < 0) break
                off += n
            }
            return off == magic.size &&
                magic[0] == 0x7f.toByte() &&
                magic[1] == 0x45.toByte() &&
                magic[2] == 0x4c.toByte() &&
                magic[3] == 0x46.toByte()
        }
    }
}
