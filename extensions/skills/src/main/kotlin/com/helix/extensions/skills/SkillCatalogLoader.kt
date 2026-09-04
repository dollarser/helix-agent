package com.helix.extensions.skills

import java.nio.file.Files
import java.nio.file.Path

class SkillCatalogLoader(
    private val skillLoader: SkillLoader = SkillLoader(),
) {
    fun scan(
        root: Path,
        source: SkillSource,
    ): SkillCatalog {
        require(Files.isDirectory(root) && !Files.isSymbolicLink(root)) {
            "Skill catalog root must be a regular, non-symlink directory"
        }
        val directories =
            Files.list(root).use { paths ->
                paths
                    .filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }
                    .sorted(compareBy { it.fileName.toString() })
                    .toList()
            }
        val entries = mutableListOf<SkillCatalogEntry>()
        val diagnostics = mutableListOf<SkillCatalogDiagnostic>()
        val names = mutableSetOf<String>()
        directories.forEach { directory ->
            try {
                val entry = skillLoader.load(directory, source).catalogEntry
                if (!names.add(entry.name)) {
                    diagnostics +=
                        SkillCatalogDiagnostic(
                            directoryName = directory.fileName.toString(),
                            message = "Duplicate skill name: ${entry.name}",
                        )
                } else {
                    entries += entry
                }
            } catch (failure: InvalidSkillException) {
                diagnostics +=
                    SkillCatalogDiagnostic(
                        directoryName = directory.fileName.toString(),
                        message = failure.message ?: "Invalid skill",
                    )
            }
        }
        return SkillCatalog(
            entries = entries.sortedBy { it.name },
            diagnostics = diagnostics.sortedBy { it.directoryName },
        )
    }
}
