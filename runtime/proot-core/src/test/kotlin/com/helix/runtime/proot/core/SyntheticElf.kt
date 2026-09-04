package com.helix.runtime.proot.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a minimal valid ELF64 LE object with one `PT_LOAD` segment for
 * installer/smoke tests (configurable `p_align` and `e_machine`).
 */
object SyntheticElf {
    fun elf64Le(
        pAlign: Long = 0x4000L,
        machine: Int = 183,
    ): ByteArray {
        val buf = ByteBuffer.allocate(64 + 56).order(ByteOrder.LITTLE_ENDIAN)
        // e_ident: ELFMAG(4) + ELFCLASS64 + ELFDATA2LSB + EV_CURRENT + padding
        for ((i, v) in intArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0).withIndex()) {
            buf.put(i, v.toByte())
        }
        putShort(buf, 16, 2) // e_type EXEC
        putShort(buf, 18, machine) // e_machine
        putInt(buf, 20, 1) // e_version
        putLong(buf, 24, 0L) // e_entry
        putLong(buf, 32, 64L) // e_phoff
        putLong(buf, 40, 0L) // e_shoff
        putInt(buf, 48, 0) // e_flags
        putShort(buf, 52, 64) // e_ehsize
        putShort(buf, 54, 56) // e_phentsize
        putShort(buf, 56, 1) // e_phnum
        putShort(buf, 58, 0) // e_shentsize
        putShort(buf, 60, 0) // e_shnum
        putShort(buf, 62, 0) // e_shstrndx
        // one PT_LOAD program header (56 bytes at e_phoff = 64)
        putInt(buf, 64, 1) // p_type PT_LOAD
        putInt(buf, 68, 5) // p_flags RWX
        putLong(buf, 72, 0L) // p_offset
        putLong(buf, 80, 0L) // p_vaddr
        putLong(buf, 88, 0L) // p_paddr
        putLong(buf, 96, 0x1000L) // p_filesz
        putLong(buf, 104, 0x1000L) // p_memsz
        putLong(buf, 112, pAlign) // p_align
        return buf.array()
    }

    private fun putShort(
        buf: ByteBuffer,
        offset: Int,
        value: Int,
    ) {
        buf.putShort(offset, value.toShort())
    }

    private fun putInt(
        buf: ByteBuffer,
        offset: Int,
        value: Int,
    ) {
        buf.putInt(offset, value)
    }

    private fun putLong(
        buf: ByteBuffer,
        offset: Int,
        value: Long,
    ) {
        buf.putLong(offset, value)
    }
}
