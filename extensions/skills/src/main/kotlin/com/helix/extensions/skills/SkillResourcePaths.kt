package com.helix.extensions.skills

import java.nio.ByteBuffer
import java.security.MessageDigest

/** One path contract for filesystem and host-packaged data; scripts are never readable resources. */
internal fun skillResourceParts(relativePath: String): List<String> {
    require(relativePath.isNotBlank() && relativePath.length <= SkillImportLimits().maxRelativePathLength) {
        "Invalid skill resource path"
    }
    require(!relativePath.startsWith('/') && '\\' !in relativePath && '\u0000' !in relativePath) {
        "Skill resource path must be relative"
    }
    val parts = relativePath.split('/')
    require(parts.size > 1 && parts.none { it.isBlank() || it == "." || it == ".." }) {
        "Skill resource path contains traversal"
    }
    require(parts.first() == "references" || parts.first() == "assets") {
        "Only references and assets can be read as skill resources"
    }
    return parts
}

/** Existing snapshot framing, shared with packaged references; names and bytes both bind the identity. */
internal fun MessageDigest.skillFileHeader(
    path: String,
    size: Long,
) {
    val bytes = path.toByteArray(Charsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
    update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(size).array())
}
