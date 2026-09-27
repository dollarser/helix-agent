package com.helix.core.workspace

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A locator alone cannot prove continuity after deletion and recreation. Never follow a root symlink. */
object WorkspaceDirectoryIdentity {
    fun witness(root: Path): String {
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink) { "Workspace directory unavailable" }
        val key = requireNotNull(attributes.fileKey()) { "Backend cannot verify directory identity" }
        return "$key:${attributes.creationTime()}"
    }
}
