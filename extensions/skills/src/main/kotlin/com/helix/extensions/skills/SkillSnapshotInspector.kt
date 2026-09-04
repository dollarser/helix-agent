package com.helix.extensions.skills

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString

@Suppress("TooManyFunctions")
internal class SkillSnapshotInspector(
    private val limits: SkillImportLimits,
    private val skillLoader: SkillLoader,
) {
    fun inspect(
        skillDirectory: Path,
        source: SkillSource,
    ): SkillImportPreview {
        val document = skillLoader.load(skillDirectory, source)
        val files = inventory(skillDirectory)
        return SkillImportPreview(
            name = document.catalogEntry.name,
            description = document.catalogEntry.description,
            snapshotHash = treeHash(skillDirectory, files),
            source = source,
            compatibility = document.compatibility,
            declaredAllowedTools = document.allowedTools,
            files = files,
        )
    }

    fun copyDirectory(
        source: Path,
        destination: Path,
    ) {
        require(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
        var fileCount = 0
        var totalBytes = 0L
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    rejectSymlinkOrSpecial(directory, attributes, directory = true)
                    val relative = safeRelative(source, directory)
                    Files.createDirectories(destination.resolve(relative))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    rejectSymlinkOrSpecial(file, attributes, directory = false)
                    val relative = safeRelative(source, file)
                    fileCount += 1
                    totalBytes = checkedSizes(fileCount, totalBytes, attributes.size(), relative)
                    openNoFollow(file).use { input ->
                        Files
                            .newOutputStream(
                                destination.resolve(relative),
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                            ).use { output -> input.copyTo(output) }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun inventory(root: Path): List<SkillFileInfo> {
        val paths = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    rejectSymlinkOrSpecial(directory, attributes, directory = true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    rejectSymlinkOrSpecial(file, attributes, directory = false)
                    paths.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        if (paths.size > limits.maxFiles) invalid("Skill contains more than ${limits.maxFiles} files")
        var totalBytes = 0L
        return paths
            .sortedBy { safeRelative(root, it).invariantSeparatorsPathString }
            .map { file ->
                val relative = safeRelative(root, file)
                val size = Files.size(file)
                totalBytes = checkedSizes(paths.size, totalBytes, size, relative)
                SkillFileInfo(
                    relativePath = relative.invariantSeparatorsPathString,
                    sizeBytes = size,
                    sha256 = hashFile(file),
                    kind = kind(relative),
                )
            }
    }

    private fun treeHash(
        root: Path,
        files: List<SkillFileInfo>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            val pathBytes = file.relativePath.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
            digest.update(pathBytes)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.sizeBytes).array())
            openNoFollow(root.resolve(file.relativePath)).use { input -> updateDigest(digest, input) }
        }
        return digest.digest().toHex()
    }

    private fun hashFile(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openNoFollow(file).use { input -> updateDigest(digest, input) }
        return digest.digest().toHex()
    }

    private fun updateDigest(
        digest: MessageDigest,
        input: InputStream,
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            digest.update(buffer, 0, count)
        }
    }

    private fun checkedSizes(
        fileCount: Int,
        currentTotal: Long,
        size: Long,
        relative: Path,
    ): Long {
        if (fileCount > limits.maxFiles) invalid("Skill contains more than ${limits.maxFiles} files")
        if (size < 0 || size > limits.maxSingleFileBytes) {
            invalid("Skill file exceeds the single-file limit: $relative")
        }
        val nextTotal = currentTotal + size
        if (nextTotal < currentTotal || nextTotal > limits.maxTotalBytes) {
            invalid("Skill exceeds the total uncompressed size limit")
        }
        return nextTotal
    }

    private fun safeRelative(
        root: Path,
        path: Path,
    ): Path {
        val relative = root.relativize(path)
        if (relative.toString().length > limits.maxRelativePathLength) {
            invalid("Skill relative path exceeds the length limit")
        }
        if (relative.any { it.toString() == ".." }) invalid("Skill path escapes its root")
        return relative
    }

    private fun rejectSymlinkOrSpecial(
        path: Path,
        attributes: BasicFileAttributes,
        directory: Boolean,
    ) {
        if (attributes.isSymbolicLink || Files.isSymbolicLink(path)) invalid("Skill must not contain symlinks: $path")
        if (directory && !attributes.isDirectory) invalid("Expected a directory: $path")
        if (!directory && !attributes.isRegularFile) invalid("Skill must contain only regular files: $path")
    }

    private fun openNoFollow(path: Path): InputStream =
        Channels.newInputStream(
            Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)),
        )

    private fun kind(relative: Path): SkillFileKind =
        when {
            relative.nameCount == 1 && relative.fileName.toString() == SkillLoader.SKILL_FILE_NAME -> {
                SkillFileKind.MANIFEST
            }

            relative.firstOrNull()?.toString() == "scripts" -> {
                SkillFileKind.SCRIPT
            }

            relative.firstOrNull()?.toString() == "references" -> {
                SkillFileKind.REFERENCE
            }

            relative.firstOrNull()?.toString() == "assets" -> {
                SkillFileKind.ASSET
            }

            else -> {
                SkillFileKind.OTHER
            }
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun invalid(message: String): Nothing = throw InvalidSkillImportException(message)

    companion object {
        private const val COPY_BUFFER_SIZE = 16 * 1024
    }
}
