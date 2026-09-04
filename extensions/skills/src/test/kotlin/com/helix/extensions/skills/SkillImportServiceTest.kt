package com.helix.extensions.skills

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillImportServiceTest {
    @Test
    fun `directory import previews inventory and commits immutable content-addressed snapshot`() {
        val roots = roots()
        val source = skillDirectory(roots.source, "review-skill", body = "first body")
        write(source, "scripts/run.sh", "echo safe")
        write(source, "references/guide.md", "guide")
        write(source, "assets/template.txt", "template")
        val service = SkillImportService(roots.staging)

        val staged = service.stageDirectory(source)

        assertEquals("review-skill", staged.preview.name)
        assertEquals(1, staged.preview.scripts.size)
        assertEquals(2, staged.preview.resources.size)
        assertEquals(4, staged.preview.files.size)
        assertEquals(64, staged.preview.snapshotHash.length)
        assertTrue(Files.exists(source.resolve("SKILL.md")))

        val snapshot = service.commit(staged, roots.installed)

        assertEquals(staged.preview.snapshotHash, snapshot.snapshotHash)
        assertEquals(
            roots.installed
                .resolve("review-skill")
                .resolve(snapshot.snapshotHash)
                .resolve("review-skill"),
            snapshot.directory,
        )
        assertTrue(Files.exists(snapshot.directory.resolve("scripts/run.sh")))
        assertTrue(Files.exists(source.resolve("SKILL.md")))
        assertFalse(Files.exists(staged.stagingDirectory))
    }

    @Test
    fun `resource-only update creates a new snapshot without replacing the pinned version`() {
        val roots = roots()
        val source = skillDirectory(roots.source, "versioned-skill", body = "body")
        write(source, "references/data.txt", "version one")
        val service = SkillImportService(roots.staging)
        val first = service.commit(service.stageDirectory(source), roots.installed)
        Files.writeString(source.resolve("references/data.txt"), "version two")

        val second = service.commit(service.stageDirectory(source), roots.installed)

        assertNotEquals(first.snapshotHash, second.snapshotHash)
        assertTrue(Files.exists(first.directory))
        assertTrue(Files.exists(second.directory))
        assertEquals("version one", Files.readString(first.directory.resolve("references/data.txt")))
        assertEquals("version two", Files.readString(second.directory.resolve("references/data.txt")))
    }

    @Test
    fun `zip import accepts root and matching wrapper layouts`() {
        val roots = roots()
        val rootZip =
            zip(
                roots.source.resolve("root.zip"),
                mapOf(
                    "SKILL.md" to manifest("root-zip"),
                    "scripts/run.js" to "console.log('ok')",
                ),
            )
        val wrappedZip =
            zip(
                roots.source.resolve("wrapped.zip"),
                mapOf(
                    "wrapped-zip/SKILL.md" to manifest("wrapped-zip"),
                    "wrapped-zip/references/readme.md" to "reference",
                ),
            )
        val service = SkillImportService(roots.staging)

        val root = service.stageZip(rootZip)
        val wrapped = service.stageZip(wrappedZip)

        assertEquals("root-zip", root.preview.name)
        assertEquals(listOf("scripts/run.js"), root.preview.scripts.map { it.relativePath })
        assertEquals("wrapped-zip", wrapped.preview.name)
        assertEquals(listOf("references/readme.md"), wrapped.preview.resources.map { it.relativePath })
        service.discard(root)
        service.discard(wrapped)
    }

    @Test
    fun `zip import rejects traversal absolute backslash duplicate and multiple-root layouts`() {
        val roots = roots()
        val service = SkillImportService(roots.staging)
        val malicious =
            listOf(
                mapOf("../escape" to "x", "SKILL.md" to manifest("bad")),
                mapOf("/absolute" to "x", "SKILL.md" to manifest("bad")),
                mapOf("one/SKILL.md" to manifest("one"), "two/file" to "x"),
            )
        malicious.forEachIndexed { index, files ->
            val archive = zip(roots.source.resolve("bad-$index.zip"), files)
            assertThrows("malicious archive $index", InvalidSkillImportException::class.java) {
                service.stageZip(archive)
            }
        }

        val backslash = roots.source.resolve("backslash.zip")
        ZipOutputStream(Files.newOutputStream(backslash)).use { output ->
            output.putNextEntry(ZipEntry("SKILL.md"))
            output.write(manifest("bad").toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("folder\\escape"))
            output.write("x".toByteArray())
            output.closeEntry()
        }
        assertThrows(InvalidSkillImportException::class.java) { service.stageZip(backslash) }

        val duplicate = roots.source.resolve("duplicate.zip")
        ZipArchiveOutputStream(duplicate).use { output ->
            addEntry(output, "SKILL.md", manifest("duplicate"))
            addEntry(output, "same", "one")
            output.putArchiveEntry(ZipArchiveEntry("same/"))
            output.closeArchiveEntry()
        }
        assertThrows(InvalidSkillImportException::class.java) { service.stageZip(duplicate) }
    }

    @Test
    fun `zip import rejects unix symlinks and special files`() {
        val roots = roots()
        val service = SkillImportService(roots.staging)
        val symlinkZip = roots.source.resolve("symlink.zip")
        ZipArchiveOutputStream(symlinkZip).use { output ->
            addEntry(output, "SKILL.md", manifest("symlink"))
            val link = ZipArchiveEntry("references/link")
            link.unixMode = UnixStat.LINK_FLAG or UnixStat.DEFAULT_LINK_PERM
            output.putArchiveEntry(link)
            output.write("../../outside".toByteArray())
            output.closeArchiveEntry()
        }

        assertThrows(InvalidSkillImportException::class.java) { service.stageZip(symlinkZip) }
    }

    @Test
    fun `zip import enforces file count size total and compression ratio bounds`() {
        val roots = roots()
        val countArchive =
            zip(
                roots.source.resolve("count.zip"),
                mapOf(
                    "SKILL.md" to manifest("count"),
                    "one" to "1",
                    "two" to "2",
                ),
            )
        val countService = SkillImportService(roots.staging.resolve("count"), SkillImportLimits(maxFiles = 2))
        assertThrows(InvalidSkillImportException::class.java) { countService.stageZip(countArchive) }

        val largeArchive =
            zip(
                roots.source.resolve("large.zip"),
                mapOf(
                    "SKILL.md" to manifest("large"),
                    "large.bin" to "x".repeat(2_000),
                ),
            )
        val sizeService =
            SkillImportService(
                roots.staging.resolve("size"),
                SkillImportLimits(maxSingleFileBytes = 1_000, maxTotalBytes = 4_000),
            )
        assertThrows(InvalidSkillImportException::class.java) { sizeService.stageZip(largeArchive) }

        val bombArchive =
            zip(
                roots.source.resolve("ratio.zip"),
                mapOf(
                    "SKILL.md" to manifest("ratio"),
                    "zeros.bin" to "0".repeat(20_000),
                ),
            )
        val ratioService =
            SkillImportService(
                roots.staging.resolve("ratio"),
                SkillImportLimits(maxSingleFileBytes = 30_000, maxTotalBytes = 40_000, maxCompressionRatio = 5),
            )
        assertThrows(InvalidSkillImportException::class.java) { ratioService.stageZip(bombArchive) }
    }

    @Test
    fun `zip import rejects directory entry floods and fractional compression ratio overflow`() {
        val roots = roots()
        val directoryFlood = roots.source.resolve("directories.zip")
        ZipArchiveOutputStream(directoryFlood).use { output ->
            addEntry(output, "SKILL.md", manifest("directories"))
            repeat(4) { index ->
                output.putArchiveEntry(ZipArchiveEntry("empty-$index/"))
                output.closeArchiveEntry()
            }
        }
        val entryLimited =
            SkillImportService(
                roots.staging.resolve("entries"),
                SkillImportLimits(maxArchiveEntries = 4),
            )
        assertThrows(InvalidSkillImportException::class.java) { entryLimited.stageZip(directoryFlood) }

        val compressed = "0".repeat(20_003)
        val fractionalArchive =
            zip(
                roots.source.resolve("fractional-ratio.zip"),
                mapOf("SKILL.md" to manifest("fractional-ratio"), "payload" to compressed),
            )
        val compressedSize =
            ZipFile.builder().setPath(fractionalArchive).get().use { archive ->
                archive.getEntry("payload").compressedSize
            }
        assertTrue(compressedSize > 0)
        assertTrue(compressed.length.toLong() % compressedSize != 0L)
        val exactFloorLimit = compressed.length.toLong() / compressedSize
        val ratioLimited =
            SkillImportService(
                roots.staging.resolve("fractional"),
                SkillImportLimits(
                    maxSingleFileBytes = 30_000,
                    maxTotalBytes = 40_000,
                    maxCompressionRatio = exactFloorLimit,
                ),
            )
        assertThrows(InvalidSkillImportException::class.java) { ratioLimited.stageZip(fractionalArchive) }
    }

    @Test
    fun `directory import rejects symlinks and configured limits`() {
        val roots = roots()
        val source = skillDirectory(roots.source, "linked-skill", body = "body")
        val outside = Files.writeString(roots.source.resolve("outside.txt"), "outside")
        try {
            Files.createSymbolicLink(source.resolve("reference-link"), outside)
        } catch (_: UnsupportedOperationException) {
            assumeTrue("Symbolic links are not supported", false)
        }
        val service = SkillImportService(roots.staging)
        assertThrows(InvalidSkillImportException::class.java) { service.stageDirectory(source) }

        val many = skillDirectory(roots.source, "many-files", body = "body")
        write(many, "one", "1")
        write(many, "two", "2")
        val limited = SkillImportService(roots.staging.resolve("limited"), SkillImportLimits(maxFiles = 2))
        assertThrows(InvalidSkillImportException::class.java) { limited.stageDirectory(many) }
    }

    @Test
    fun `commit rejects post-review mutation and installed symlink collision`() {
        val roots = roots()
        val source = skillDirectory(roots.source, "tamper-skill", body = "body")
        val service = SkillImportService(roots.staging)
        val staged = service.stageDirectory(source)
        Files.writeString(staged.skillDirectory.resolve("SKILL.md"), manifest("tamper-skill") + "changed")
        assertThrows(InvalidSkillImportException::class.java) { service.commit(staged, roots.installed) }

        val clean = service.stageDirectory(source)
        Files.createDirectories(roots.installed)
        val outside = Files.createTempDirectory("skill-installed-outside")
        try {
            Files.createSymbolicLink(roots.installed.resolve("tamper-skill"), outside)
        } catch (_: UnsupportedOperationException) {
            assumeTrue("Symbolic links are not supported", false)
        }
        assertThrows(IllegalArgumentException::class.java) { service.commit(clean, roots.installed) }
    }

    private fun roots(): Roots {
        val root = Files.createTempDirectory("skill-import")
        return Roots(
            source = Files.createDirectory(root.resolve("source")),
            staging = root.resolve("staging"),
            installed = root.resolve("installed"),
        )
    }

    private fun skillDirectory(
        parent: Path,
        name: String,
        body: String,
    ): Path {
        val directory = Files.createDirectory(parent.resolve(name))
        Files.writeString(directory.resolve("SKILL.md"), manifest(name) + body)
        return directory
    }

    private fun manifest(name: String): String =
        """
        ---
        name: $name
        description: A test import skill.
        allowed-tools: Bash(root:*)
        ---

        """.trimIndent() + "\n"

    private fun write(
        root: Path,
        relative: String,
        content: String,
    ) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun zip(
        path: Path,
        files: Map<String, String>,
    ): Path {
        ZipArchiveOutputStream(path).use { output ->
            files.forEach { (name, content) -> addEntry(output, name, content) }
        }
        return path
    }

    private fun addEntry(
        output: ZipArchiveOutputStream,
        name: String,
        content: String,
    ) {
        output.putArchiveEntry(ZipArchiveEntry(name))
        output.write(content.toByteArray())
        output.closeArchiveEntry()
    }

    private data class Roots(
        val source: Path,
        val staging: Path,
        val installed: Path,
    )
}
