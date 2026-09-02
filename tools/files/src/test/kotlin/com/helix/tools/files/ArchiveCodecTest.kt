package com.helix.tools.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-047 (`:tools:files:test`): the pure [ArchiveCodec] — restricted zip/tar create/extract and
 * the format-level defenses that make it fail-closed. No store is involved; this pins the codec
 * the tools build on: Zip Slip / path-traversal entry names, the symlink/device/PAX entry-type
 * whitelist, the entry-count / per-entry / total-size / expansion-ratio bounds, and corrupt /
 * truncated-archive refusal. The matrix-mandated "Zip Slip" and "膨胀比 (expansion)" fixtures are
 * here at the codec level.
 */
class ArchiveCodecTest {
    /** Generous limits so the round-trip and name tests never trip a bound by accident. */
    private val big =
        ArchiveLimits(
            maxEntries = 10_000,
            maxEntryBytes = 10L * 1024 * 1024,
            maxTotalBytes = 100L * 1024 * 1024,
            maxExpansionRatio = 100_000,
        )

    private class Extracted {
        val files = HashMap<String, ByteArray>()
        val dirs = LinkedHashSet<String>()
    }

    private fun collect(
        format: ArchiveFormat,
        bytes: ByteArray,
        limits: ArchiveLimits = big,
    ): Extracted {
        val out = Extracted()
        ArchiveCodec.extract(format, bytes, limits) { m ->
            when (m) {
                is ArchiveDir -> out.dirs.add(m.name)
                is ArchiveFile -> out.files[m.name] = m.content
            }
        }
        return out
    }

    /** Runs [block], expecting an [ArchiveCodecException], and returns its stable [reason]. */
    private fun codecReason(block: () -> Unit): String =
        try {
            block()
            throw AssertionError("expected ArchiveCodecException, none thrown")
        } catch (e: ArchiveCodecException) {
            e.reason
        }

    private fun throwOn(name: String): String = codecReason { ArchiveCodec.validateMemberName(name) }

    // ── round trips ──────────────────────────────────────────────────────────────────────

    @Test
    fun zipRoundTripsFilesAndDirs() {
        val members =
            listOf<ArchiveMember>(
                ArchiveDir("a"),
                ArchiveFile("a/one.txt", "hello".toByteArray()),
                ArchiveFile("two.txt", "world".toByteArray()),
            )
        val got = collect(ArchiveFormat.ZIP, ArchiveCodec.create(ArchiveFormat.ZIP, members))
        assertEquals(setOf("a"), got.dirs)
        assertEquals(2, got.files.size)
        assertTrue(got.files["a/one.txt"]!!.contentEquals("hello".toByteArray()))
        assertTrue(got.files["two.txt"]!!.contentEquals("world".toByteArray()))
    }

    @Test
    fun tarRoundTripsFilesAndDirs() {
        val members =
            listOf<ArchiveMember>(
                ArchiveDir("a"),
                ArchiveFile("a/one.txt", "hello".toByteArray()),
                ArchiveFile("two.txt", "world".toByteArray()),
            )
        val got = collect(ArchiveFormat.TAR, ArchiveCodec.create(ArchiveFormat.TAR, members))
        assertEquals(setOf("a"), got.dirs)
        assertEquals(2, got.files.size)
        assertTrue(got.files["a/one.txt"]!!.contentEquals("hello".toByteArray()))
        assertTrue(got.files["two.txt"]!!.contentEquals("world".toByteArray()))
    }

    // ── Zip Slip / path-traversal entry names ────────────────────────────────────────────

    @Test
    fun aZipSlipEntryNameIsRefused() {
        val bytes = ArchiveTestFixtures.zipWithEntry("../evil.txt", "pwned".toByteArray())
        assertEquals("BAD_NAME_DOT", codecReason { collect(ArchiveFormat.ZIP, bytes) })
    }

    @Test
    fun anAbsoluteZipEntryNameIsRefused() {
        val bytes = ArchiveTestFixtures.zipWithEntry("/etc/passwd", "pwned".toByteArray())
        assertEquals("BAD_NAME_ABSOLUTE", codecReason { collect(ArchiveFormat.ZIP, bytes) })
    }

    // ── expansion-ratio / count / size bounds ────────────────────────────────────────────

    @Test
    fun anExpansionBombIsRefused() {
        // 512 KiB of one byte compresses to a few hundred bytes, far above the 100:1 allowance.
        val bytes = ArchiveTestFixtures.zipWithEntry("bomb.txt", ByteArray(512 * 1024) { 0x41 })
        assertEquals(
            "EXPANSION_EXCEEDED",
            codecReason {
                collect(
                    ArchiveFormat.ZIP,
                    bytes,
                    ArchiveLimits(10_000, 16L * 1024 * 1024, 32L * 1024 * 1024, 100),
                )
            },
        )
    }

    @Test
    fun tooManyEntriesIsRefused() {
        val bytes = ArchiveTestFixtures.zipWithMembers(List(5) { "f$it.txt" to "x".toByteArray() })
        assertEquals(
            "TOO_MANY_ENTRIES",
            codecReason {
                collect(
                    ArchiveFormat.ZIP,
                    bytes,
                    ArchiveLimits(2, big.maxEntryBytes, big.maxTotalBytes, big.maxExpansionRatio),
                )
            },
        )
    }

    @Test
    fun anOversizeEntryIsRefused() {
        val bytes = ArchiveTestFixtures.zipWithEntry("big.txt", ByteArray(100))
        assertEquals(
            "ENTRY_TOO_LARGE",
            codecReason {
                collect(
                    ArchiveFormat.ZIP,
                    bytes,
                    ArchiveLimits(10, 50, big.maxTotalBytes, big.maxExpansionRatio),
                )
            },
        )
    }

    @Test
    fun anOversizeTotalIsRefused() {
        val bytes = ArchiveTestFixtures.zipWithMembers(listOf("a.txt" to ByteArray(60), "b.txt" to ByteArray(60)))
        assertEquals(
            "TOTAL_TOO_LARGE",
            codecReason { collect(ArchiveFormat.ZIP, bytes, ArchiveLimits(10, 100, 100, big.maxExpansionRatio)) },
        )
    }

    // ── entry-type whitelist (tar) ───────────────────────────────────────────────────────

    @Test
    fun aSymlinkTarEntryIsRefused() {
        assertEquals(
            "UNSUPPORTED_TAR_ENTRY",
            codecReason {
                collect(
                    ArchiveFormat.TAR,
                    ArchiveTestFixtures.tarBlock("link", 0x32.toByte(), ByteArray(0)),
                )
            },
        )
    }

    @Test
    fun aPaxTarEntryIsRefused() {
        assertEquals(
            "UNSUPPORTED_TAR_PAX",
            codecReason {
                collect(
                    ArchiveFormat.TAR,
                    ArchiveTestFixtures.tarBlock("hdr", 0x78.toByte(), "k=v".toByteArray()),
                )
            },
        )
    }

    @Test
    fun aCorruptTarChecksumIsRefused() {
        assertEquals(
            "BAD_TAR_CHECKSUM",
            codecReason {
                collect(
                    ArchiveFormat.TAR,
                    ArchiveTestFixtures.tarBlock("f.txt", 0x30.toByte(), "data".toByteArray(), checksumDelta = 1),
                )
            },
        )
    }

    @Test
    fun aTruncatedTarIsRefused() {
        val full = ArchiveTestFixtures.tarBlock("f.txt", 0x30.toByte(), "data".toByteArray())
        assertEquals("TRUNCATED", codecReason { collect(ArchiveFormat.TAR, full.copyOfRange(0, full.size - 3)) })
    }

    // ── the name validator, in isolation ─────────────────────────────────────────────────

    @Test
    fun theNameValidatorRejectsTraversalAndControlForms() {
        assertEquals("a/b", ArchiveCodec.validateMemberName("a/b"))
        assertEquals("BAD_NAME_EMPTY", throwOn(""))
        assertEquals("BAD_NAME_ABSOLUTE", throwOn("/abs"))
        assertEquals("BAD_NAME_EMPTY_SEGMENT", throwOn("a//b"))
        assertEquals("BAD_NAME_DOT", throwOn("a/../b"))
        assertEquals("BAD_NAME_DOT", throwOn("a/./b"))
        assertEquals("BAD_NAME_BACKSLASH", throwOn("a\\b"))
        assertEquals("BAD_NAME_CONTROL", throwOn("a b"))
    }
}
