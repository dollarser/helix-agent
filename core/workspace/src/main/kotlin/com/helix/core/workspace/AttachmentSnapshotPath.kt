package com.helix.core.workspace

/** Closed locations owned by chat import/normalization, never a project or external user file. */
object AttachmentSnapshotPath {
    fun contains(path: FileScopePath): Boolean =
        path.scopeId == "app" &&
            (IMPORTED.matches(path.relativePath) || DERIVED.matches(path.relativePath))

    private val IMPORTED = Regex("input/attachments/att_[a-f0-9]{32}/[^/]+")
    private val DERIVED = Regex("input/attachment-derived/[a-f0-9]{64}/image-[^/]+/normalized\\.(png|jpg|webp)")
}
