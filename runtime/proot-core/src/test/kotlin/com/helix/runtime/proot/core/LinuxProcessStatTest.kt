package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxProcessStatTest {
    @Test fun commParenthesesDoNotRetargetProcessIdentity() {
        val fields = listOf("S", "31", "40", "40") + List(15) { "0" } + "12345"
        val result = LinuxProcessStat.parse("41 (a ) tricky (name)) " + fields.joinToString(" "))
        assertEquals(LinuxProcessStat(41, 31, 40, 40, 12345), result)
        assertThrows(IllegalArgumentException::class.java) { LinuxProcessStat.parse("41 (x) S 1 2") }
    }
}
