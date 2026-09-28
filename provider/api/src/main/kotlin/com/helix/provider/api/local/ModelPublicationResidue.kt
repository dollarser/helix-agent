package com.helix.provider.api.local

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Opaque confirmation token for abandoned publication copies, never installed model assets. */
class ModelPublicationResidue internal constructor(
    internal val owner: ModelAssetStore,
    internal val revision: Long,
    internal val entries: List<PublicationPartial>,
) {
    val bytes: Long get() = entries.sumOf { it.bytes }
    val count: Int get() = entries.size
}

internal data class PublicationPartial(
    val name: String,
    val bytes: Long,
    val modified: java.nio.file.attribute.FileTime,
    val key: String?,
)

internal fun publicationPartials(root: File): List<PublicationPartial> {
    check(!Files.isSymbolicLink(root.toPath()))
    return checkNotNull(root.listFiles())
        .mapNotNull { file ->
            if (!file.name.matches(Regex("[a-f0-9]{64}\\.part"))) return@mapNotNull null
            val attrs =
                Files.readAttributes(
                    file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (!attrs.isRegularFile) return@mapNotNull null
            PublicationPartial(file.name, attrs.size(), attrs.lastModifiedTime(), attrs.fileKey()?.toString())
        }.sortedBy { it.name }
}
