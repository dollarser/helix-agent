package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Only a kernel-observed directory path and matching descriptor identity may produce this alternative. */
internal fun verifiedSharedDirectory(
    grantedRoots: List<Pair<String, Path>>,
    descriptorPath: String,
    descriptorIdentity: Pair<Long, Long>,
    identityAt: (Path) -> Pair<Long, Long>,
): String {
    val candidate = java.io.File(descriptorPath).toPath()
    require(candidate.isAbsolute && candidate.toRealPath() == candidate)
    val (scope, root) =
        grantedRoots
            .map { it.first to it.second.toRealPath() }
            .filter { candidate.startsWith(it.second) }
            .maxByOrNull { it.second.nameCount }
            ?: error("Directory is outside granted storage")
    require(Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
    require(identityAt(candidate) == descriptorIdentity) { "Directory identity changed" }
    Files.newDirectoryStream(candidate).use { it.iterator().hasNext() }
    return FileScopePath(scope, root.relativize(candidate).toString()).toModelReference()
}
