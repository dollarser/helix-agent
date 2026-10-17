package com.helix.runtime.proot.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes owned runtime data without traversing links to a workspace or another environment. */
object RuntimeTreeDeletion {
    /** A missing tree is already removed; IO failures and partial removal are reported as false. */
    @Suppress("SwallowedException") // The caller receives a failed removal, never a success receipt.
    fun remove(root: File): Boolean =
        try {
            val path = root.toPath()
            if (!Files.notExists(path, NOFOLLOW_LINKS)) {
                Files.walkFileTree(
                    path,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            directory: Path,
                            error: IOException?,
                        ): FileVisitResult {
                            if (error != null) throw error
                            Files.deleteIfExists(directory)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
            Files.notExists(path, NOFOLLOW_LINKS)
        } catch (error: IOException) {
            false
        }
}
