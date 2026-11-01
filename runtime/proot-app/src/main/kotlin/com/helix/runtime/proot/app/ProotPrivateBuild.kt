package com.helix.runtime.proot.app

import java.io.File
import java.nio.file.Files

/** Optional out-of-tree build locations. Never changes project files, build flags or publication behavior. */
internal fun privateBuildEnvironment(temporary: File): Map<String, String> {
    for (name in listOf("helix-build", "helix-cache")) {
        val directory = File(temporary, name)
        require(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdir())
        require(directory.canonicalFile.parentFile == temporary.canonicalFile)
    }
    return mapOf("HELIX_BUILD_DIR" to "/tmp/helix-build", "XDG_CACHE_HOME" to "/tmp/helix-cache", "TMPDIR" to "/tmp")
}
