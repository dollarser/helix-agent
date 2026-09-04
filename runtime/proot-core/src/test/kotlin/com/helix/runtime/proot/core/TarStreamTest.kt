package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class TarStreamTest {
    @Rule
    @JvmField
    var tmp = TemporaryFolder()

    private fun sampleArchive(): ByteArray =
        SyntheticTar.archive(
            SyntheticTar.Entry("etc/", type = '5', mode = 0x1ED),
            SyntheticTar.Entry("etc/motd", content = "hello\n"),
            SyntheticTar.Entry("usr/", type = '5'),
            SyntheticTar.Entry("usr/bin/", type = '5'),
            SyntheticTar.Entry("usr/bin/tool", content = "BIN".repeat(100), mode = 0x1ED),
            SyntheticTar.Entry("usr/bin/tool.link", type = '2', linkTarget = "tool"),
            SyntheticTar.Entry("empty.txt", content = ByteArray(0)),
        )

    @Test
    fun scanListsValidatedMembersInOrder() {
        val members = TarStream.scan(ByteArrayInputStream(sampleArchive()))
        assertEquals(
            listOf(
                "etc",
                "etc/motd",
                "usr",
                "usr/bin",
                "usr/bin/tool",
                "usr/bin/tool.link",
                "empty.txt",
            ),
            members.map {
                it.path
            },
        )
        assertEquals(
            listOf(
                TarMemberType.DIR,
                TarMemberType.FILE,
                TarMemberType.DIR,
                TarMemberType.DIR,
                TarMemberType.FILE,
                TarMemberType.SYMLINK,
                TarMemberType.FILE,
            ),
            members.map { it.type },
        )
        assertEquals("tool", members[5].linkTarget)
        assertEquals(300L, members[4].size)
    }

    @Test
    fun extractRecreatesTheTree() {
        val dest = tmp.newFolder("out")
        val result = TarStream.extract(ByteArrayInputStream(sampleArchive()), dest)
        assertEquals(7, result.members.size)
        assertEquals("hello\n", dest.resolve("etc/motd").readText())
        assertEquals("BIN".repeat(100), dest.resolve("usr/bin/tool").readText())
        assertEquals("", dest.resolve("empty.txt").readText())
        assertTrue(dest.resolve("etc").isDirectory)
        val link =
            java.nio.file.Files
                .readSymbolicLink(dest.toPath().resolve("usr/bin/tool.link"))
        assertEquals("tool", link.toString())
        assertEquals(306L, result.extractedBytes) // 6 + 300 + 0
    }

    @Test
    fun aLongNameMemberIsExpanded() {
        val longName = "usr/share/doc/" + "x".repeat(90)
        val archive =
            SyntheticTar.archive(
                SyntheticTar.Entry("L", content = longName.encodeToByteArray(), type = 'L', mode = 0x1A4),
                SyntheticTar.Entry("payload", content = "data"),
            )
        val dest = tmp.newFolder("out")
        TarStream.extract(ByteArrayInputStream(archive), dest)
        assertEquals("data", dest.resolve(longName).readText())
    }

    @Test
    fun aPartialFinalBlockIsTruncation() {
        val full = sampleArchive()
        assertTarFails(full.copyOf(full.size - 77))
    }

    @Test
    fun aTruncatedPayloadIsTruncation() {
        val full = sampleArchive()
        // cut inside the 300-byte payload of usr/bin/tool
        assertTarFails(full.copyOf(512 * 5 + 100))
    }

    @Test
    fun aCorruptHeaderChecksumFails() {
        val bad = sampleArchive().copyOf()
        bad[10] = (bad[10].toInt() xor 0xFF).toByte()
        assertTarFails(bad)
    }

    @Test
    fun aHardLinkMemberIsRejected() {
        val evil = SyntheticTar.archive(SyntheticTar.forbidden("hard", type = '1'))
        assertTarFails(evil)
    }

    @Test
    fun aCharacterDeviceMemberIsRejected() {
        val evil = SyntheticTar.archive(SyntheticTar.forbidden("dev/null", type = '3'))
        assertTarFails(evil)
    }

    @Test
    fun aFifoMemberIsRejected() {
        val evil = SyntheticTar.archive(SyntheticTar.forbidden("pipe", type = '6'))
        assertTarFails(evil)
    }

    @Test
    fun anAbsolutePathMemberIsNormalizedNotEscaped() {
        // tar convention: leading slash stripped; the joined path stays inside the root.
        val archive = SyntheticTar.archive(SyntheticTar.Entry("/etc/passwd", content = "x"))
        val dest = tmp.newFolder("out")
        TarStream.extract(ByteArrayInputStream(archive), dest)
        assertEquals("x", dest.resolve("etc/passwd").readText())
        assertFalse(dest.parentFile!!.resolve("etc").exists())
    }

    private fun assertTarFails(bytes: ByteArray) {
        val dest = tmp.newFolder("fail")
        try {
            TarStream.extract(ByteArrayInputStream(bytes), dest)
            throw AssertionError("expected TarStreamException")
        } catch (e: TarStreamException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}
