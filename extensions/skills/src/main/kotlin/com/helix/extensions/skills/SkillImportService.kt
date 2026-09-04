package com.helix.extensions.skills

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Suppress("TooManyFunctions")
class SkillImportService(
    private val stagingRoot: Path,
    private val limits: SkillImportLimits = SkillImportLimits(),
    skillLoader: SkillLoader = SkillLoader(),
) {
    private val inspector = SkillSnapshotInspector(limits, skillLoader)

    init {
        require(limits.maxFiles > 0) { "Skill file limit must be positive" }
        require(limits.maxArchiveEntries > 0) { "Skill archive entry limit must be positive" }
        require(limits.maxSingleFileBytes > 0) { "Skill single-file limit must be positive" }
        require(limits.maxTotalBytes >= limits.maxSingleFileBytes) {
            "Skill total size limit must cover the single-file limit"
        }
        require(limits.maxArchiveBytes > 0) { "Skill archive size limit must be positive" }
        require(limits.maxCompressionRatio > 0) { "Skill compression-ratio limit must be positive" }
        require(limits.maxRelativePathLength > 0) { "Skill relative-path limit must be positive" }
        Files.createDirectories(stagingRoot)
        require(Files.isDirectory(stagingRoot) && !Files.isSymbolicLink(stagingRoot)) {
            "Skill staging root must be a regular, non-symlink directory"
        }
    }

    fun stageDirectory(sourceDirectory: Path): StagedSkillImport =
        stage { destination ->
            inspector.copyDirectory(sourceDirectory, destination.resolve(sourceDirectory.fileName.toString()))
        }

    fun stageZip(zipPath: Path): StagedSkillImport {
        if (!Files.isRegularFile(zipPath) || Files.isSymbolicLink(zipPath)) invalid("ZIP must be a regular file")
        if (Files.size(zipPath) > limits.maxArchiveBytes) invalid("ZIP exceeds the archive size limit")
        return stage { destination -> extractZip(zipPath, destination) }
    }

    fun commit(
        staged: StagedSkillImport,
        installedRoot: Path,
    ): SkillSnapshotRef {
        requireOwnedStaging(staged)
        val currentPreview = inspector.inspect(staged.skillDirectory, SkillSource.USER_IMPORTED)
        if (currentPreview != staged.preview) invalid("Staged skill changed after review")
        Files.createDirectories(installedRoot)
        require(Files.isDirectory(installedRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(installedRoot)) {
            "Installed skill root must be a regular, non-symlink directory"
        }
        val nameRoot = installedRoot.resolve(currentPreview.name)
        if (Files.exists(nameRoot, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(nameRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(nameRoot)) {
                "Installed skill name path must be a regular, non-symlink directory"
            }
        } else {
            Files.createDirectory(nameRoot)
        }
        val snapshotContainer = nameRoot.resolve(currentPreview.snapshotHash)
        val target = snapshotContainer.resolve(currentPreview.name)
        if (Files.exists(snapshotContainer, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(snapshotContainer)) invalid("Existing snapshot path must not be a symlink")
            val installed = inspector.inspect(target, SkillSource.USER_IMPORTED)
            if (installed.snapshotHash != currentPreview.snapshotHash) {
                invalid("Existing snapshot path does not match its content hash")
            }
            deleteTree(staged.stagingDirectory)
            return snapshot(currentPreview, target)
        }
        Files.createDirectory(snapshotContainer)
        try {
            try {
                Files.move(staged.skillDirectory, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.skillDirectory, target)
            }
        } catch (failure: IOException) {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(snapshotContainer)
            throw failure
        }
        deleteTree(staged.stagingDirectory)
        return snapshot(currentPreview, target)
    }

    fun discard(staged: StagedSkillImport) {
        requireOwnedStaging(staged)
        deleteTree(staged.stagingDirectory)
    }

    private fun stage(populate: (Path) -> Unit): StagedSkillImport {
        val stagingDirectory = Files.createTempDirectory(stagingRoot, STAGING_PREFIX)
        val provisional = stagingDirectory.resolve(PROVISIONAL_DIRECTORY)
        Files.createDirectory(provisional)
        return try {
            populate(provisional)
            val manifest = locateManifestRoot(provisional)
            val document =
                if (manifest == provisional) {
                    SkillLoader().loadUnmatchedDirectory(manifest, SkillSource.USER_IMPORTED)
                } else {
                    SkillLoader().load(manifest, SkillSource.USER_IMPORTED)
                }
            val namedDirectory = stagingDirectory.resolve(document.catalogEntry.name)
            if (manifest != namedDirectory) Files.move(manifest, namedDirectory)
            removeEmptyWrapper(provisional, namedDirectory)
            val preview = inspector.inspect(namedDirectory, SkillSource.USER_IMPORTED)
            StagedSkillImport(stagingDirectory, namedDirectory, preview)
        } catch (failure: InvalidSkillImportException) {
            stageFailure(stagingDirectory, failure)
        } catch (failure: InvalidSkillException) {
            stageFailure(stagingDirectory, failure)
        } catch (failure: IOException) {
            stageFailure(stagingDirectory, failure)
        }
    }

    private fun extractZip(
        zipPath: Path,
        destination: Path,
    ) {
        ZipFile.builder().setPath(zipPath).get().use { zip ->
            val entries = mutableListOf<ZipArchiveEntry>()
            val enumeration = zip.entries
            while (enumeration.hasMoreElements()) {
                if (entries.size >= limits.maxArchiveEntries) invalid("ZIP contains too many entries")
                entries += enumeration.nextElement()
            }
            extractEntries(zip, planEntries(entries), destination)
        }
    }

    private fun extractEntries(
        zip: ZipFile,
        plannedEntries: List<PlannedZipEntry>,
        destination: Path,
    ) {
        var fileCount = 0
        var totalBytes = 0L
        plannedEntries.forEach { plannedEntry ->
            val target = destination.resolve(plannedEntry.relativePath).normalize()
            if (!target.startsWith(destination)) invalid("ZIP entry escapes the skill root")
            if (plannedEntry.entry.isDirectory) {
                Files.createDirectories(target)
            } else {
                fileCount += 1
                if (fileCount > limits.maxFiles) invalid("ZIP contains too many files")
                totalBytes = extractFile(zip, plannedEntry, target, totalBytes)
            }
        }
    }

    private fun extractFile(
        zip: ZipFile,
        planned: PlannedZipEntry,
        target: Path,
        currentTotal: Long,
    ): Long {
        Files.createDirectories(target.parent)
        val compressedSize = planned.entry.compressedSize
        val declaredSize = planned.entry.size
        checkDeclaredZipSizes(planned.relativePath, declaredSize, compressedSize)
        val written =
            zip.getInputStream(planned.entry).use { input ->
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                    copyBounded(input, output, currentTotal)
                }
            }
        if (declaredSize >= 0 && declaredSize != written) invalid("ZIP entry size does not match extracted data")
        checkCompressionRatio(written, compressedSize)
        return currentTotal + written
    }

    private fun planEntries(entries: List<ZipArchiveEntry>): List<PlannedZipEntry> {
        if (entries.isEmpty()) invalid("ZIP is empty")
        val seen = mutableSetOf<String>()
        val safeEntries =
            entries.map { entry ->
                if (entry.isUnixSymlink) invalid("ZIP must not contain symlinks")
                rejectSpecialUnixEntry(entry)
                if (entry.generalPurposeBit.usesEncryption()) invalid("Encrypted ZIP entries are not supported")
                if (entry.rawName.any { it == BACKSLASH_BYTE }) invalid("ZIP entry names must use forward slashes")
                val components = safeZipComponents(entry.name, entry.isDirectory)
                val canonical = components.joinToString("/")
                if (!seen.add(canonical)) invalid("ZIP contains duplicate entries")
                SafeZipEntry(entry, components)
            }
        val rootManifest = safeEntries.any { !it.entry.isDirectory && it.components == listOf("SKILL.md") }
        val wrapper = if (rootManifest) null else commonWrapper(safeEntries)
        return safeEntries.mapNotNull { safe ->
            val relative = if (wrapper == null) safe.components else safe.components.drop(1)
            if (relative.isEmpty()) {
                if (!safe.entry.isDirectory) invalid("ZIP wrapper must be a directory")
                null
            } else {
                PlannedZipEntry(safe.entry, relative.fold(Path.of("")) { path, part -> path.resolve(part) })
            }
        }
    }

    private fun commonWrapper(entries: List<SafeZipEntry>): String {
        val wrapper = entries.first().components.firstOrNull() ?: invalid("ZIP contains an empty entry name")
        if (entries.any { it.components.firstOrNull() != wrapper }) invalid("ZIP must contain exactly one skill")
        val hasManifest = entries.any { !it.entry.isDirectory && it.components == listOf(wrapper, "SKILL.md") }
        if (!hasManifest) invalid("ZIP does not contain a root SKILL.md")
        return wrapper
    }

    private fun safeZipComponents(
        rawName: String,
        directory: Boolean,
    ): List<String> {
        if (rawName.isEmpty() || '\u0000' in rawName || '\\' in rawName) invalid("ZIP contains an invalid entry name")
        if (rawName.startsWith('/') || DRIVE_PATH.matches(rawName)) invalid("ZIP contains an absolute path")
        val name = if (directory && rawName.endsWith('/')) rawName.dropLast(1) else rawName
        if (name.isEmpty() || "//" in name) invalid("ZIP contains an invalid entry path")
        val components = name.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) invalid("ZIP contains path traversal")
        if (name.length > limits.maxRelativePathLength) invalid("ZIP entry path exceeds the length limit")
        return components
    }

    private fun rejectSpecialUnixEntry(entry: ZipArchiveEntry) {
        val fileType = entry.unixMode and UNIX_FILE_TYPE_MASK
        if (fileType != 0 && fileType != UNIX_REGULAR_FILE && fileType != UNIX_DIRECTORY) {
            invalid("ZIP must contain only regular files and directories")
        }
    }

    private fun checkDeclaredZipSizes(
        path: Path,
        size: Long,
        compressedSize: Long,
    ) {
        if (size > limits.maxSingleFileBytes) invalid("ZIP entry exceeds the single-file limit: $path")
        if (size > limits.maxTotalBytes) invalid("ZIP entry exceeds the total size limit")
        if (size >= 0) checkCompressionRatio(size, compressedSize)
    }

    private fun checkCompressionRatio(
        size: Long,
        compressedSize: Long,
    ) {
        if (size <= 0 || compressedSize < 0) return
        if (compressedSize == 0L) invalid("ZIP entry exceeds the compression-ratio limit")
        val quotient = size / compressedSize
        val exceedsLimit =
            quotient > limits.maxCompressionRatio ||
                (quotient == limits.maxCompressionRatio && size % compressedSize != 0L)
        if (exceedsLimit) {
            invalid("ZIP entry exceeds the compression-ratio limit")
        }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        currentTotal: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return written
            written += count
            if (written > limits.maxSingleFileBytes || currentTotal + written > limits.maxTotalBytes) {
                invalid("ZIP extracted data exceeds configured limits")
            }
            output.write(buffer, 0, count)
        }
    }

    private fun locateManifestRoot(provisional: Path): Path {
        if (Files.isRegularFile(provisional.resolve(SkillLoader.SKILL_FILE_NAME))) return provisional
        val children = Files.list(provisional).use { it.toList() }
        if (children.size != 1 ||
            !Files.isDirectory(children.single())
        ) {
            invalid("Import must contain exactly one skill")
        }
        val wrapped = children.single()
        if (!Files.isRegularFile(wrapped.resolve(SkillLoader.SKILL_FILE_NAME))) invalid("Import has no root SKILL.md")
        return wrapped
    }

    private fun removeEmptyWrapper(
        provisional: Path,
        namedDirectory: Path,
    ) {
        if (provisional == namedDirectory || !Files.exists(provisional)) return
        Files.list(provisional).use { children ->
            if (children.findAny().isPresent) invalid("Import contains files outside the skill directory")
        }
        Files.delete(provisional)
    }

    private fun requireOwnedStaging(staged: StagedSkillImport) {
        val normalizedRoot = stagingRoot.toAbsolutePath().normalize()
        val normalizedStage = staged.stagingDirectory.toAbsolutePath().normalize()
        require(
            normalizedStage.parent == normalizedRoot && normalizedStage.fileName.toString().startsWith(STAGING_PREFIX),
        ) {
            "Staged import is not owned by this service"
        }
        require(staged.skillDirectory.parent == staged.stagingDirectory) { "Invalid staged skill path" }
    }

    private fun snapshot(
        preview: SkillImportPreview,
        target: Path,
    ): SkillSnapshotRef = SkillSnapshotRef(preview.name, preview.snapshotHash, target, preview.source)

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun stageFailure(
        stagingDirectory: Path,
        failure: Exception,
    ): Nothing {
        deleteTree(stagingDirectory)
        val mapped =
            when (failure) {
                is InvalidSkillImportException -> {
                    failure
                }

                is InvalidSkillException -> {
                    InvalidSkillImportException(failure.message ?: "Invalid skill", failure)
                }

                else -> {
                    InvalidSkillImportException("Unable to stage skill import", failure)
                }
            }
        throw mapped
    }

    private fun invalid(message: String): Nothing = throw InvalidSkillImportException(message)

    private data class SafeZipEntry(
        val entry: ZipArchiveEntry,
        val components: List<String>,
    )

    private data class PlannedZipEntry(
        val entry: ZipArchiveEntry,
        val relativePath: Path,
    )

    companion object {
        private const val STAGING_PREFIX = ".skill-stage-"
        private const val PROVISIONAL_DIRECTORY = "incoming"
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private const val UNIX_FILE_TYPE_MASK = 0xF000
        private const val UNIX_REGULAR_FILE = 0x8000
        private const val UNIX_DIRECTORY = 0x4000
        private const val BACKSLASH_BYTE: Byte = 92
        private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }
}
