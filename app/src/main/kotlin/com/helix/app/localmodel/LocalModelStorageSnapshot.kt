package com.helix.app.localmodel

/** Logical file bytes, not total application usage or filesystem allocation. */
class LocalModelStorageSnapshot internal constructor(
    val installedBytes: Long,
    val downloadBytes: Long,
    val downloadCount: Int,
    internal val revision: Long,
    internal val entries: List<LocalModelPartial>,
    internal val publication: com.helix.provider.api.local.ModelPublicationResidue,
)

internal data class LocalModelPartial(
    val name: String,
    val bytes: Long,
    val modified: java.nio.file.attribute.FileTime,
    val fileKey: String?,
)
