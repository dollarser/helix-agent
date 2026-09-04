package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarPathSafetyTest {
    @Test
    fun plainRelativePathsAreKept() {
        assertEquals("usr/bin/sh", TarPathSafety.normalizeMemberPath("usr/bin/sh"))
    }

    @Test
    fun aLeadingSlashIsStrippedLikeGnuTar() {
        // The locked archive ships leading-slash names (tar convention); stripping
        // them is what keeps every joined path inside the extraction root.
        assertEquals("bin/sh", TarPathSafety.normalizeMemberPath("/bin/sh"))
        assertEquals("etc", TarPathSafety.normalizeMemberPath("/etc"))
    }

    @Test
    fun dotSegmentsAreDropped() {
        assertEquals("usr/lib", TarPathSafety.normalizeMemberPath("./usr/./lib"))
    }

    @Test
    fun parentSegmentsAreRejected() {
        assertRejects("a/../b")
        assertRejects("../b")
        assertRejects("/../b")
        assertRejects("a/b/../../c")
    }

    @Test
    fun emptySegmentsAreRejected() {
        assertRejects("a//b")
        assertRejects("")
        assertRejects("/")
    }

    @Test
    fun controlCharactersAndBackslashesAreRejected() {
        assertRejects("a\u0000b")
        assertRejects("a\nb")
        assertRejects("a\\b")
    }

    @Test
    fun symlinkTargetsInsideTheTreeAreAccepted() {
        assertEquals("/bin/busybox", TarPathSafety.validateSymlinkTarget("/bin/busybox", "bin/ash"))
        assertEquals("rg", TarPathSafety.validateSymlinkTarget("rg", "usr/bin/rg.bak"))
        // in-tree traversal: /etc/os-release -> ../usr/lib/os-release (real Alpine shape)
        assertEquals(
            "../usr/lib/os-release",
            TarPathSafety.validateSymlinkTarget("../usr/lib/os-release", "etc/os-release"),
        )
        // deep relative hop that stays inside
        assertEquals("c/../../d", TarPathSafety.validateSymlinkTarget("c/../../d", "a/b"))
    }

    @Test
    fun symlinkEscapesAreRejected() {
        assertTargetRejects("../../evil", "etc/pwn")
        assertTargetRejects("/../../evil", "etc/pwn")
        assertTargetRejects("..", "sh")
        assertTargetRejects("", "sh")
        assertTargetRejects("a\u0000b", "sh")
        assertTargetRejects("a\\b", "sh")
        assertTargetRejects("a//b", "sh")
    }

    private fun assertRejects(name: String) {
        try {
            TarPathSafety.normalizeMemberPath(name)
            throw AssertionError("expected rejection of member path: $name")
        } catch (e: TarStreamException) {
            assertTrue(
                e.message!!.contains("member") || e.message!!.contains("control") || e.message!!.contains("backslash"),
            )
        }
    }

    private fun assertTargetRejects(
        target: String,
        member: String,
    ) {
        try {
            TarPathSafety.validateSymlinkTarget(target, member)
            throw AssertionError("expected rejection of symlink target: $target")
        } catch (e: TarStreamException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}
