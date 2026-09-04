@file:Suppress(
    "EmptyCatchBlock", // the empty catch IS the assertion: the fail() before it proves the throw
    "SwallowedException", // same idiom; nothing to preserve from an expected rejection
    "LongMethod", // one strict-codec scenario suite, not to be fragmented
)

package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HXA-084: the bounded job archive (input extraction safety + output archiving)
 * is the transfer boundary between the two signed APKs; every check here is
 * fail-closed and the happy path round-trips byte-for-byte through the manifest.
 */
class JobArchiveTest {
    @Rule
    @JvmField
    var tmp = TemporaryFolder()

    // ---------------------------------------------------------------- helpers

    private fun writeFile(
        dir: File,
        path: String,
        content: String,
    ): File {
        val file = File(dir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun buildArchive(
        dir: File,
        files: Map<String, String>,
    ): File {
        val entries =
            files
                .map { (path, content) ->
                    val file = writeFile(dir, path, content)
                    JobManifestEntry(
                        path,
                        sha256HexBytes(content.toByteArray()),
                        content.toByteArray().size.toLong(),
                    )
                }.sortedBy { it.path }
        val manifest = JobManifestCodec.encode(JobManifest(entries))
        val out = File(dir, "archive.zip")
        JobZipWriter(out.outputStream()).use { writer ->
            writer.writeManifest(manifest)
            files.toSortedMap().forEach { (path, _) -> writer.writeEntry(path, File(dir, path)) }
        }
        return out
    }

    private fun sha256HexBytes(bytes: ByteArray): String =
        java.security
            .MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun rawZip(entries: List<Pair<String, Int>>): File {
        // (name, unixModeType) -> a ZIP built from raw central-directory records so
        // the external attributes field (the only carrier of the unix mode) is
        // controlled byte-for-byte.
        val local = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { (name, modeType) ->
            val nameBytes = name.toByteArray()
            val content = "x".toByteArray()
            val externalAttributes = (modeType.toLong() shl 16).toInt()
            // local file header
            local.write(intLe(0x04034b50))
            local.write(shortLe(20)) // version needed
            local.write(shortLe(0)) // flags
            local.write(shortLe(0)) // method: stored
            local.write(shortLe(0)) // time
            local.write(shortLe(0)) // date
            local.write(intLe(0)) // crc
            local.write(intLe(content.size))
            local.write(intLe(content.size))
            local.write(shortLe(nameBytes.size))
            local.write(shortLe(0)) // extra
            local.write(nameBytes)
            local.write(content)
            // central directory record
            central.write(intLe(0x02014b50))
            central.write(shortLe(20)) // version made by
            central.write(shortLe(20)) // version needed
            central.write(shortLe(0)) // flags
            central.write(shortLe(0)) // method
            central.write(shortLe(0)) // time
            central.write(shortLe(0)) // date
            central.write(intLe(0)) // crc
            central.write(intLe(content.size))
            central.write(intLe(content.size))
            central.write(shortLe(nameBytes.size))
            central.write(shortLe(0)) // extra length
            central.write(shortLe(0)) // comment length
            central.write(shortLe(0)) // disk start
            central.write(shortLe(0)) // internal attrs
            central.write(intLe(externalAttributes.toInt()))
            central.write(nameBytes)
        }
        val localBytes = local.toByteArray()
        val centralBytes = central.toByteArray()
        val out = File(tmp.root, "raw.zip")
        out.outputStream().use { o ->
            o.write(localBytes)
            o.write(centralBytes)
            // EOCD
            o.write(intLe(0x06054b50))
            o.write(shortLe(0)) // disk
            o.write(shortLe(0)) // disk with cd
            o.write(shortLe(entries.size))
            o.write(shortLe(entries.size))
            o.write(intLe(centralBytes.size))
            o.write(intLe(localBytes.size))
            o.write(shortLe(0)) // comment length
        }
        return out
    }

    private fun intLe(v: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0..3) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
        return b
    }

    private fun shortLe(v: Int): ByteArray {
        val b = ByteArray(2)
        for (i in 0..1) b[i] = ((v shr (8 * i)) and 0xFF).toByte()
        return b
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun anArchiveRoundTripsThroughExtractAndReverify() {
        val src = tmp.newFolder("src")
        val archive =
            buildArchive(
                src,
                mapOf(
                    "a.txt" to "hello",
                    "dir/nested/b.bin" to "world-bytes",
                ),
            )
        val dest = tmp.newFolder("dest")
        val extraction = ZipJobExtractor.extract(archive, dest)

        assertEquals(2, extraction.manifest.entries.size)
        assertEquals("a.txt", extraction.manifest.entries[0].path)
        assertEquals(5L, extraction.manifest.entries[0].size)
        val extracted = File(dest, "dir/nested/b.bin")
        assertEquals("world-bytes", extracted.readText())
        // The manifest hash the client would promise is derivable from the
        // extraction alone.
        assertTrue(extraction.manifestSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun anEmptyInputIsLegalWhenTheManifestIsTheOnlyEntry() {
        val src = tmp.newFolder("src")
        val manifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val out = File(src, "empty.zip")
        JobZipWriter(out.outputStream()).use { writer -> writer.writeManifest(manifest) }
        val dest = tmp.newFolder("dest")
        val extraction = ZipJobExtractor.extract(out, dest)
        assertTrue(extraction.manifest.entries.isEmpty())
    }

    @Test
    fun theManifestEntryMustBeFirstAndExact() {
        val src = tmp.newFolder("src")
        val file = writeFile(src, "a.txt", "hello")
        val entry = JobManifestEntry("a.txt", sha256HexBytes("hello".toByteArray()), 5L)
        val manifest = JobManifestCodec.encode(JobManifest(listOf(entry)))
        // The writer hashes the REAL file; if the manifest says something else,
        // close() must fail the archive.
        val lyingManifest =
            JobManifestCodec.encode(
                JobManifest(listOf(entry.copy(sha256 = "0".repeat(64)))),
            )
        val out = File(src, "lying.zip")
        try {
            JobZipWriter(out.outputStream()).use { writer ->
                writer.writeManifest(lyingManifest)
                writer.writeEntry("a.txt", file)
            }
            fail("expected the drift to be rejected")
        } catch (e: JobArchiveException) {
            assertTrue(e.message!!.contains("manifest"))
        }
    }

    // ---------------------------------------------------------------- manifest codec

    @Test
    fun theManifestCodecIsStrict() {
        val entry = JobManifestEntry("a.txt", sha256HexBytes("x".toByteArray()), 1L)
        val manifest = JobManifestCodec.encode(JobManifest(listOf(entry)))
        assertEquals(listOf(entry), JobManifestCodec.parse(manifest).entries)

        // Unknown key
        try {
            JobManifestCodec.parse("""{"schemaVersion":1,"entries":[],"extra":1}""")
            fail("unknown key accepted")
        } catch (e: JobArchiveException) {
        }
        // Missing key
        try {
            JobManifestCodec.parse("""{"schemaVersion":1}""")
            fail("missing key accepted")
        } catch (e: JobArchiveException) {
        }
        // Wrong schema version
        try {
            JobManifestCodec.parse("""{"schemaVersion":2,"entries":[]}""")
            fail("wrong schema version accepted")
        } catch (e: JobArchiveException) {
        }
        // encode itself refuses the unsorted input:
        try {
            JobManifestCodec.encode(
                JobManifest(
                    listOf(
                        entry.copy(path = "z.txt"),
                        entry,
                    ),
                ),
            )
            fail("unsorted encode accepted")
        } catch (e: IllegalArgumentException) {
        }
        // A raw unsorted document is rejected by parse as well
        val rawUnsorted =
            """{"schemaVersion":1,"entries":[{"path":"z.txt","sha256":"${"0".repeat(
                64,
            )}","size":1},{"path":"a.txt","sha256":"${"0".repeat(64)}","size":1}]}"""
        try {
            JobManifestCodec.parse(rawUnsorted)
            fail("unsorted parse accepted")
        } catch (e: JobArchiveException) {
        }
        // Duplicate path
        val rawDup =
            """{"schemaVersion":1,"entries":[{"path":"a.txt","sha256":"${"0".repeat(
                64,
            )}","size":1},{"path":"a.txt","sha256":"${"0".repeat(64)}","size":1}]}"""
        try {
            JobManifestCodec.parse(rawDup)
            fail("duplicate path accepted")
        } catch (e: JobArchiveException) {
        }
        // Malformed hash
        try {
            JobManifestCodec.parse("""{"schemaVersion":1,"entries":[{"path":"a.txt","sha256":"xyz","size":1}]}""")
            fail("malformed hash accepted")
        } catch (e: JobArchiveException) {
        }
        // Negative size
        try {
            JobManifestCodec.parse(
                """{"schemaVersion":1,"entries":[{"path":"a.txt","sha256":"${"0".repeat(64)}","size":-1}]}""",
            )
            fail("negative size accepted")
        } catch (e: JobArchiveException) {
        }
        assertEquals(manifest, JobManifestCodec.encode(JobManifest(listOf(entry))))
    }

    // ---------------------------------------------------------------- path safety

    @Test
    fun traversalAndAbsolutePathsAreRejected() {
        for (bad in listOf(
            "/etc/passwd",
            "a/../../etc/passwd",
            "..",
            "a//b",
            "a/./b",
            "a\\b",
            "C:evil",
            "a/b/",
            "",
        )) {
            try {
                JobPath.validate(bad)
                fail("accepted: $bad")
            } catch (e: JobArchiveException) {
            }
        }
        JobPath.validate("a/b/c.txt") // legal
    }

    @Test
    fun aZipSlipEntryIsRejectedAtExtraction() {
        val src = tmp.newFolder("src")
        val outside = tmp.newFolder("outside")
        val malicious = File(src, "evil.zip")
        ZipOutputStream(malicious.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside/pwned"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
        }
        val dest = tmp.newFolder("dest")
        try {
            ZipJobExtractor.extract(malicious, dest)
            fail("zip slip accepted")
        } catch (e: JobArchiveException) {
        }
        assertTrue(!File(outside, "pwned").exists())
    }

    @Test
    fun aSymlinkEntryIsRejectedByTheCentralDirectoryScan() {
        // 0xA000 = symlink
        val archive = rawZip(listOf("link" to 0xA000))
        val dest = tmp.newFolder("dest")
        try {
            ZipJobExtractor.extract(archive, dest)
            fail("symlink entry accepted")
        } catch (e: JobArchiveException) {
            assertTrue(e.message!!.contains("non-regular"))
        }
        // 0x8000 = regular file: passes the scan (the rest of the checks then
        // apply to the content/manifest).
        val regular = rawZip(listOf("ok" to 0x8000))
        ZipCentralDirectoryScan.rejectNonRegularEntries(regular)
        // 0 = no unix info: also passes.
        ZipCentralDirectoryScan.rejectNonRegularEntries(rawZip(listOf("plain" to 0x0000)))
    }

    @Test
    fun anArchiveEntryMissingFromTheManifestIsRejected() {
        // A well-formed writer can never produce this drift (its close() rejects
        // it), so build the hostile archive with a plain ZipOutputStream: a file
        // entry that the manifest does not list.
        val src = tmp.newFolder("src")
        val manifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val out = File(src, "extra.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(JobArchiveLimits.MANIFEST_ENTRY))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("a.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        val dest = tmp.newFolder("dest")
        try {
            ZipJobExtractor.extract(out, dest)
            fail("manifest/entry mismatch accepted")
        } catch (e: JobArchiveException) {
        }
        assertTrue(!File(dest, "a.txt").exists())
    }

    @Test
    fun aTruncatedArchiveFailsClosed() {
        val src = tmp.newFolder("src")
        val archive = buildArchive(src, mapOf("a.txt" to "hello world"))
        val truncated = File(src, "trunc.zip")
        val bytes = archive.readBytes()
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))
        val dest = tmp.newFolder("dest")
        try {
            ZipJobExtractor.extract(truncated, dest)
            fail("truncated archive accepted")
        } catch (e: Exception) {
        }
        // The dest must not look successful.
        assertTrue(!File(dest, "a.txt").exists() || File(dest, "a.txt").length() < 11L)
    }
}
