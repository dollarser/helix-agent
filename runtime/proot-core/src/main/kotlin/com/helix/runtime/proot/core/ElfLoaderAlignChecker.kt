package com.helix.runtime.proot.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The result of scanning one file for ELF `PT_LOAD` segment alignment (HXA-081; architecture
 * doc local-code-execution section 6.4: 验证 ELF `LOAD` alignment，16 KiB 页设备阻断的
 * 依据).
 *
 * [isElf] false → the file is not an ELF object (a script, a text file, a tar member header…):
 * alignment is not applicable. [error] is non-null only when the file IS an ELF but its
 * header/program table is structurally malformed (a truncated or corrupt ELF must fail
 * closed, not be skipped). [minLoadAlign] is the minimum `p_align` over all `PT_LOAD`
 * segments (0 when the object has none); a 16 KiB-page device requires
 * `minLoadAlign >= 16384` on EVERY executable/shared object of the runtime.
 * [machine] is `e_machine` (for the ABI gate, HXA-081: every runtime ELF must match the
 * lock ABI), null when not applicable.
 */
data class ElfScanResult(
    val isElf: Boolean,
    val error: String?,
    val minLoadAlign: Long,
    val machine: Long? = null,
) {
    /** Whether this file satisfies a minimum alignment requirement (fail-closed on error). */
    fun meets(minAlignment: Long): Boolean = isElf && error == null && minLoadAlign >= minAlignment
}

/**
 * Pure-JVM ELF `PT_LOAD` alignment scanner (HXA-081). Runs in three places over the SAME
 * code: the build-time asset gate (every fetched binary + every rootfs ELF), the on-device
 * installer pre-activation check (HXA-082) and the 16 KiB device-compat decision (HXA-086).
 *
 * Supports ELF32/ELF64, little- and big-endian. Only the bytes needed to walk
 * `e_phoff`/`e_phnum` and each `PT_LOAD.p_align` are read; no code is executed or
 * relocated. Malformed structures throw nothing — they return [ElfScanResult.error] so a
 * single corrupt file cannot take down a whole-tree scan.
 */
object ElfLoaderAlignChecker {
    /** The minimum `p_align` a runtime binary must have to load on a 4 KiB-page device. */
    const val PAGE_SIZE_4KIB: Long = 4096L

    /** The minimum `p_align` a runtime binary must have to load on a 16 KiB-page device. */
    const val PAGE_SIZE_16KIB: Long = 16_384L

    /** ELF machine ID `EM_AARCH64` (the `arm64-v8a` lock ABI). */
    const val MACHINE_AARCH64: Long = 183L

    /** ELF machine ID `EM_X86_64` (the `x86_64` lock ABI). */
    const val MACHINE_X86_64: Long = 62L

    private const val PT_LOAD: Long = 1L

    /**
     * Scans one file. Non-ELF bytes → [ElfScanResult] with [ElfScanResult.isElf] false and
     * no error; a structurally malformed ELF fails closed via [ElfScanResult.error]; a
     * valid ELF reports the minimum `PT_LOAD` alignment and `e_machine`.
     */
    fun scan(bytes: ByteArray): ElfScanResult {
        val header = parseHeader(bytes)
        if (!header.isElf || header.error != null) {
            return ElfScanResult(header.isElf, header.error, 0L, header.machine)
        }
        val loads = readLoadSegments(bytes, header)
        val error = loads.error ?: if (loads.minAlign == null) "no PT_LOAD segment" else null
        return ElfScanResult(true, error, loads.minAlign ?: 0L, header.machine)
    }

    /**
     * Scans every file of a tree (path → bytes) and returns the violating paths whose ELF
     * does not meet [minAlignment] (non-ELF files never violate). Order is the input order
     * (deterministic when the caller passes a sorted map).
     */
    fun scanTreeViolations(
        files: Map<String, ByteArray>,
        minAlignment: Long,
    ): List<String> {
        val violations = mutableListOf<String>()
        files.forEach { (path, bytes) ->
            val result = scan(bytes)
            if (result.isElf && !result.meets(minAlignment)) {
                violations += path
            }
        }
        return violations
    }

    private data class Header(
        val isElf: Boolean,
        val error: String?,
        val machine: Long?,
        val is64: Boolean,
        val order: ByteOrder,
        val phOff: Long,
        val phEntSize: Int,
        val phNum: Int,
    )

    private data class LoadInfo(
        val minAlign: Long?,
        val error: String?,
    )

    private sealed interface Entry {
        object Skip : Entry

        data class Error(
            val message: String,
        ) : Entry

        data class Align(
            val align: Long,
        ) : Entry
    }

    private fun isElfMagic(bytes: ByteArray): Boolean =
        bytes[0] == 0x7f.toByte() &&
            bytes[1] == 0x45.toByte() && // 'E'
            bytes[2] == 0x4c.toByte() && // 'L'
            bytes[3] == 0x46.toByte() // 'F'

    /** e_ident facts: ELF magic, EI_DATA validity, EI_CLASS, byte order. */
    private data class Identity(
        val magic: Boolean,
        val eiData: Int,
        val eiOk: Boolean,
        val is64: Boolean,
        val order: ByteOrder,
    )

    private data class ProgramPointer(
        val phOff: Long,
        val phEntSize: Int,
        val phNum: Int,
    )

    /**
     * Parses e_ident/e_machine/e_phoff/e_phentsize/e_phnum and validates the header.
     * [Header.error] is null for a usable header (including a phnum of 0 — the
     * "no PT_LOAD segment" finding is made by [readLoadSegments]); `e_machine` is only
     * reported when the phoff/structure check passed or failed with `e_phoff out of
     * bounds`, mirroring the fail-closed contract of the public [scan].
     */
    private fun parseHeader(bytes: ByteArray): Header {
        val id = readIdentity(bytes)
        val ptr = readProgramPointer(bytes, id)
        val error = headerError(bytes, id, ptr)
        val machineReported = error == null || error == "e_phoff out of bounds"
        val machine = if (id.eiOk && machineReported) machineOf(bytes, id.order) else null
        return Header(id.magic, error, machine, id.is64, id.order, ptr.phOff, ptr.phEntSize, ptr.phNum)
    }

    private fun readIdentity(bytes: ByteArray): Identity {
        val magic = bytes.size >= 20 && isElfMagic(bytes)
        val eiData = if (magic) bytes[5].toInt() else 0
        val eiOk = magic && eiData in 1..2
        val is64 = eiOk && bytes[4].toInt() == 2
        val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        return Identity(magic, eiData, eiOk, is64, order)
    }

    /**
     * e_phoff (ELF64 @ 0x20 / ELF32 @ 0x1C), e_phentsize and e_phnum. Read only when the
     * header is complete — a truncated header is reported by [headerError], never by a
     * buffer overflow.
     */
    private fun readProgramPointer(
        bytes: ByteArray,
        id: Identity,
    ): ProgramPointer {
        val sizeOk = id.eiOk && bytes.size >= (if (id.is64) 64 else 52)
        if (!sizeOk) return ProgramPointer(-1L, 0, 0)
        val buf = ByteBuffer.wrap(bytes).order(id.order)
        val phOff =
            if (id.is64) buf.getLong(0x20).coerceAtLeast(-1L) else buf.getInt(0x1C).toLong() and 0xFFFFFFFFL
        // ELF64: e_phentsize @ 0x36, e_phnum @ 0x38. ELF32: e_phentsize @ 0x2A, e_phnum @ 0x2C.
        val phEntSize = (if (id.is64) buf.getShort(0x36) else buf.getShort(0x2A)).toInt() and 0xFFFF
        val phNum = (if (id.is64) buf.getShort(0x38) else buf.getShort(0x2C)).toInt() and 0xFFFF
        return ProgramPointer(phOff, phEntSize, phNum)
    }

    // Check order is part of the fail-closed contract: a zero phnum is reported as
    // "no PT_LOAD segment" even when e_phoff points at/over the end (mirrors the
    // original scan order: phOff<0 → phNum==0 → phOff>=size).
    private fun headerError(
        bytes: ByteArray,
        id: Identity,
        ptr: ProgramPointer,
    ): String? =
        when {
            !id.magic -> null
            !id.eiOk -> "unknown EI_DATA: ${bytes[5]}"
            bytes.size < (if (id.is64) 64 else 52) -> "truncated ELF${if (id.is64) 64 else 32} header"
            ptr.phOff < 0 -> "e_phoff out of bounds"
            ptr.phNum == 0 -> "no PT_LOAD segment"
            ptr.phOff >= bytes.size -> "e_phoff out of bounds"
            else -> null
        }

    /** `e_machine` @ 0x12 (2 bytes) in both ELF32 and ELF64. */
    private fun machineOf(
        bytes: ByteArray,
        order: ByteOrder,
    ): Long =
        ByteBuffer
            .wrap(bytes)
            .order(order)
            .getShort(0x12)
            .toInt()
            .toLong() and 0xFFFFL

    /** Walks all program headers; collects the minimum PT_LOAD alignment or the first structural error. */
    private fun readLoadSegments(
        bytes: ByteArray,
        header: Header,
    ): LoadInfo {
        var minAlign: Long? = null
        var error: String? = null
        for (index in 0 until header.phNum) {
            if (error != null) break
            when (val entry = readEntry(bytes, header, index)) {
                is Entry.Skip -> Unit
                is Entry.Error -> error = entry.message
                is Entry.Align -> minAlign = if (minAlign == null) entry.align else minAlign.coerceAtMost(entry.align)
            }
        }
        return LoadInfo(minAlign, error)
    }

    /** Reads one program header entry; bounds violations are findings, never exceptions. */
    private fun readEntry(
        bytes: ByteArray,
        header: Header,
        index: Int,
    ): Entry {
        val buf = ByteBuffer.wrap(bytes).order(header.order)
        val base = header.phOff + index * header.phEntSize.toLong()
        val baseOk = base >= 0 && base < bytes.size
        val pType = if (baseOk) buf.getInt(base.toInt()).toLong() and 0xFFFFFFFFL else 0L
        // p_align: ELF32 @ +28 (4 bytes), ELF64 @ +48 (8 bytes).
        val alignAt = base + (if (header.is64) 48L else 28L)
        val alignOk = alignAt + (if (header.is64) 8L else 4L) <= bytes.size
        val align =
            if (alignOk && pType == PT_LOAD) {
                if (header.is64) {
                    buf.getLong(alignAt.toInt()).coerceAtLeast(-1L)
                } else {
                    buf.getInt(alignAt.toInt()).toLong() and 0xFFFFFFFFL
                }
            } else {
                0L
            }
        return when {
            !baseOk -> Entry.Error("program header out of bounds")
            pType != PT_LOAD -> Entry.Skip
            !alignOk -> Entry.Error("PT_LOAD p_align out of bounds")
            align <= 0 -> Entry.Error("PT_LOAD p_align is not positive")
            else -> Entry.Align(align)
        }
    }
}
