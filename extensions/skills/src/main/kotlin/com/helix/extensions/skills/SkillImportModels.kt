package com.helix.extensions.skills

import java.nio.file.Path

data class SkillImportLimits(
    val maxFiles: Int = 256,
    val maxSingleFileBytes: Long = 4L * 1024 * 1024,
    val maxTotalBytes: Long = 16L * 1024 * 1024,
    val maxArchiveBytes: Long = 32L * 1024 * 1024,
    val maxCompressionRatio: Long = 100,
    val maxRelativePathLength: Int = 512,
)

enum class SkillFileKind {
    MANIFEST,
    SCRIPT,
    REFERENCE,
    ASSET,
    OTHER,
}

data class SkillFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: SkillFileKind,
)

data class SkillImportPreview(
    val name: String,
    val description: String,
    val snapshotHash: String,
    val source: SkillSource,
    val compatibility: String?,
    val declaredAllowedTools: String?,
    val files: List<SkillFileInfo>,
) {
    val scripts: List<SkillFileInfo> get() = files.filter { it.kind == SkillFileKind.SCRIPT }
    val resources: List<SkillFileInfo>
        get() = files.filter { it.kind == SkillFileKind.REFERENCE || it.kind == SkillFileKind.ASSET }
}

class StagedSkillImport internal constructor(
    internal val stagingDirectory: Path,
    internal val skillDirectory: Path,
    val preview: SkillImportPreview,
)

data class SkillSnapshotRef(
    val name: String,
    val snapshotHash: String,
    val directory: Path,
    val source: SkillSource,
)

class InvalidSkillImportException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
