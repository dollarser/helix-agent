package com.helix.runtime.quickjs

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ELF parser used by the HXA-050 spike tests to verify the Zipline
 * `libquickjs.so` PT_LOAD-segment alignment (Android 15+ 16 KiB page-size support,
 * doc 03 section 2.1). Pure Kotlin/JVM: runs both on the host (JVM unit test, where
 * the .so bytes are read from the Zipline AAR) and on-device (instrumented test,
 * where the .so bytes are read from the APK this process loaded).
 *
 * Supports ELF32 and ELF64, little- and big-endian. Only the fields needed for the
 * alignment check are interpreted; this is NOT a general-purpose ELF tool.
 */
object ElfLoadSegments {
    private const val ELF_MAGIC = 0x7f
    private const val PT_LOAD = 1

    private const val EI_CLASS_32 = 1
    private const val EI_CLASS_64 = 2

    /** The maximum PT_LOAD segment alignment of an ELF file, in bytes. */
    fun maxLoadSegmentAlignment(elf: ByteArray): Int {
        require(elf.size > 64) { "not an ELF file (too small: ${elf.size} bytes)" }
        require(elf[0] == ELF_MAGIC.toByte()) { "not an ELF file (bad magic)" }
        // e_ident[4] is EI_CLASS (1 = 32-bit, 2 = 64-bit); e_ident[5] is EI_DATA.
        val is64Bit = elf[4].toInt() == EI_CLASS_64
        require(elf[4].toInt() == EI_CLASS_32 || is64Bit) {
            "unknown EI_CLASS: ${elf[4].toInt() and 0xff}"
        }
        val order =
            when (elf[5].toInt() and 0xff) {
                1 -> ByteOrder.LITTLE_ENDIAN
                2 -> ByteOrder.BIG_ENDIAN
                else -> throw IllegalArgumentException("unknown ELF endianness")
            }
        val buffer = ByteBuffer.wrap(elf).order(order)
        val (programHeaderOffset, programHeaderEntrySize, programHeaderCount) =
            if (is64Bit) {
                // ELF64_Ehdr: e_phoff at 0x20 (8 bytes), e_phentsize at 0x36, e_phnum at 0x38.
                Triple(
                    buffer.getLong(0x20),
                    buffer.getShort(0x36).toInt() and 0xffff,
                    buffer.getShort(0x38).toInt() and 0xffff,
                )
            } else {
                // ELF32_Ehdr: e_phoff at 0x1C (4 bytes), e_phentsize at 0x2A, e_phnum at 0x2C.
                Triple(
                    buffer.getInt(0x1C).toLong(),
                    buffer.getShort(0x2A).toInt() and 0xffff,
                    buffer.getShort(0x2C).toInt() and 0xffff,
                )
            }
        var maxAlignment = 0
        for (index in 0 until programHeaderCount) {
            val base = (programHeaderOffset + index * programHeaderEntrySize).toInt()
            val type = buffer.getInt(base)
            if (type != PT_LOAD) {
                continue
            }
            // Elf64_Phdr: p_align at +48 (8 bytes); Elf32_Phdr: p_align at +28 (4 bytes).
            val alignment =
                if (is64Bit) {
                    buffer.getLong(base + 48).toInt()
                } else {
                    buffer.getInt(base + 28)
                }
            maxAlignment = maxOf(maxAlignment, alignment)
        }
        return maxAlignment
    }
}
