package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfLoaderAlignCheckerTest {
    // --- synthetic ELF builders -----------------------------------------------

    private fun java.nio.ByteBuffer.putBytes(values: IntArray) {
        for (v in values) put(v.toByte())
    }

    private fun elf64Le(
        phEnts: List<Triple<Long, Long, Long>>, // (type, align, flags-ignored)
        headerSize: Int = 64,
        truncate: Int = -1,
    ): ByteArray {
        val size = headerSize + phEnts.size * 56
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putBytes(intArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)) // e_ident (64-bit LE)
        buf.putShort(2) // e_type EXEC (2 bytes in ELF64)
        buf.putShort(183) // e_machine AARCH64 (2 bytes)
        buf.putInt(1) // e_version
        buf.putLong(0) // e_entry
        buf.putLong(headerSize.toLong()) // e_phoff
        buf.putLong(0) // e_shoff
        buf.putInt(0) // e_flags
        buf.putShort(headerSize.toShort()) // e_ehsize
        buf.putShort(56.toShort()) // e_phentsize
        buf.putShort(phEnts.size.toShort()) // e_phnum
        buf.putShort(0) // e_shentsize
        buf.putShort(0) // e_shnum
        buf.putShort(0) // e_shstrndx
        for ((type, align, _) in phEnts) {
            buf.putInt(type.toInt()) // p_type
            buf.putInt(5) // p_flags RWX
            buf.putLong(0) // p_offset
            buf.putLong(0) // p_vaddr
            buf.putLong(0) // p_paddr
            buf.putLong(0x1000) // p_filesz
            buf.putLong(0x1000) // p_memsz
            buf.putLong(align) // p_align
        }
        return if (truncate >= 0) buf.array().copyOf(truncate) else buf.array()
    }

    private fun elf32Le(phAlign: Long): ByteArray {
        val buf = ByteBuffer.allocate(52 + 32).order(ByteOrder.LITTLE_ENDIAN)
        buf.putBytes(intArrayOf(0x7f, 0x45, 0x4c, 0x46, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)) // e_ident (32-bit LE)
        buf.putShort(3) // e_type DYN (2 bytes in ELF32)
        buf.putShort(183) // e_machine AARCH64 (2 bytes)
        buf.putInt(1) // e_version
        buf.putInt(0) // e_entry
        buf.putInt(52) // e_phoff
        buf.putInt(0) // e_shoff
        buf.putInt(0) // e_flags
        buf.putShort(52.toShort()) // e_ehsize
        buf.putShort(32.toShort()) // e_phentsize
        buf.putShort(1.toShort()) // e_phnum
        buf.putShort(0) // e_shentsize
        buf.putShort(0) // e_shnum
        buf.putShort(0) // e_shstrndx
        buf.putInt(1) // p_type LOAD
        buf.putInt(0) // p_offset
        buf.putInt(0) // p_vaddr
        buf.putInt(0) // p_paddr
        buf.putInt(0x1000) // p_filesz
        buf.putInt(0x1000) // p_memsz
        buf.putInt(6) // p_flags RWX
        buf.putInt(phAlign.toInt()) // p_align
        return buf.array()
    }

    private fun elf64Be(align: Long): ByteArray {
        val le = elf64Le(listOf(Triple(1L, align, 0L)))
        val buf = ByteBuffer.allocate(le.size).order(ByteOrder.BIG_ENDIAN)
        buf.putBytes(intArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        buf.putShort(2) // e_type
        buf.putShort(183) // e_machine
        buf.putInt(1) // e_version
        buf.putLong(0) // e_entry
        buf.putLong(64L) // e_phoff
        buf.putLong(0) // e_shoff
        buf.putInt(0) // e_flags
        buf.putShort(64.toShort()) // e_ehsize
        buf.putShort(56.toShort()) // e_phentsize
        buf.putShort(1.toShort()) // e_phnum
        buf.putShort(0) // e_shentsize
        buf.putShort(0) // e_shnum
        buf.putShort(0) // e_shstrndx
        buf.putInt(1) // p_type LOAD
        buf.putInt(5) // p_flags
        buf.putLong(0) // p_offset
        buf.putLong(0) // p_vaddr
        buf.putLong(0) // p_paddr
        buf.putLong(0x1000) // p_filesz
        buf.putLong(0x1000) // p_memsz
        buf.putLong(align) // p_align
        return buf.array()
    }

    // --- tests ------------------------------------------------------------------

    @Test
    fun aNonElfFileIsNotAnElf() {
        val result = ElfLoaderAlignChecker.scan("shebang script".encodeToByteArray())
        assertFalse(result.isElf)
        assertNull(result.error)
        assertEquals(0L, result.minLoadAlign)
        // Non-ELF files never "violate" a threshold.
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_16KIB))
    }

    @Test
    fun anElf64LeLoadAlignIsReported() {
        // Two PT_LOAD segments (aligns 0x1000 and 0x4000) plus one DYNAMIC segment (ignored).
        val ents = listOf(Triple(1L, 0x1000L, 0L), Triple(1L, 0x4000L, 0L), Triple(2L, 1L, 0L))
        val bytes = elf64Le(ents)
        val result = ElfLoaderAlignChecker.scan(bytes)
        assertTrue(result.isElf)
        assertNull(result.error)
        assertEquals(0x1000L, result.minLoadAlign)
        assertTrue(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_4KIB))
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_16KIB))
    }

    @Test
    fun aSixteenKibAlignedElfPassesThe16KibGate() {
        val result = ElfLoaderAlignChecker.scan(elf64Le(listOf(Triple(1L, 0x4000, 0L))))
        assertTrue(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_16KIB))
    }

    @Test
    fun anElf32LeAlignIsReported() {
        val result = ElfLoaderAlignChecker.scan(elf32Le(0x1000))
        assertTrue(result.isElf)
        assertNull(result.error)
        assertEquals(0x1000L, result.minLoadAlign)
    }

    @Test
    fun anElf64BeAlignIsReported() {
        val result = ElfLoaderAlignChecker.scan(elf64Be(0x4000))
        assertTrue(result.isElf)
        assertNull(result.error)
        assertEquals(0x4000L, result.minLoadAlign)
    }

    @Test
    fun aTruncatedHeaderFailsClosed() {
        val truncated = elf64Le(listOf(Triple(1L, 0x4000, 0L)), truncate = 40)
        val result = ElfLoaderAlignChecker.scan(truncated)
        assertTrue(result.isElf)
        assertTrue(result.error!!.contains("truncated"))
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_4KIB))
    }

    @Test
    fun aPhoffOutOfBoundsFailsClosed() {
        val bytes = elf64Le(listOf(Triple(1L, 0x4000, 0L)))
        // Corrupt e_phoff to point past the end.
        bytes[0x20] = 0xFF.toByte()
        val result = ElfLoaderAlignChecker.scan(bytes)
        assertTrue(result.error!!.contains("e_phoff"))
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_4KIB))
    }

    @Test
    fun aZeroPhnumHasNoLoadSegment() {
        val bytes = elf64Le(emptyList())
        val result = ElfLoaderAlignChecker.scan(bytes)
        assertTrue(result.error!!.contains("no PT_LOAD"))
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_4KIB))
    }

    @Test
    fun aZeroAlignFailsClosed() {
        val result = ElfLoaderAlignChecker.scan(elf64Le(listOf(Triple(1L, 0L, 0L))))
        assertTrue(result.error!!.contains("p_align"))
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_4KIB))
    }

    @Test
    fun aCorruptProgramHeaderExtentFailsClosed() {
        // phnum says 2 but only one header fits.
        val bytes = elf64Le(listOf(Triple(1L, 0x4000, 0L)))
        bytes[0x38] = 2 // e_phnum = 2
        val result = ElfLoaderAlignChecker.scan(bytes)
        assertTrue(result.error!!.contains("program header"))
        assertFalse(result.meets(ElfLoaderAlignChecker.PAGE_SIZE_4KIB))
    }

    @Test
    fun aUnknownEiDataFailsClosed() {
        val bytes = elf64Le(listOf(Triple(1L, 0x4000, 0L)))
        bytes[5] = 7 // bad EI_DATA
        val result = ElfLoaderAlignChecker.scan(bytes)
        assertTrue(result.isElf)
        assertTrue(result.error!!.contains("EI_DATA"))
    }

    @Test
    fun scanTreeReportsOnlyViolatingElfs() {
        val good = elf64Le(listOf(Triple(1L, 0x4000, 0L)))
        val bad = elf64Le(listOf(Triple(1L, 0x1000, 0L)))
        val text = "hello".encodeToByteArray()
        val violations =
            ElfLoaderAlignChecker.scanTreeViolations(
                linkedMapOf("good.so" to good, "notes.txt" to text, "bad.so" to bad),
                ElfLoaderAlignChecker.PAGE_SIZE_16KIB,
            )
        assertEquals(listOf("bad.so"), violations)
    }
}
