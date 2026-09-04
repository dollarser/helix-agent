package com.helix.extensions.skills

enum class SkillSource {
    BUILT_IN,
    USER_IMPORTED,
    PROJECT,
}

data class SkillCatalogEntry(
    val name: String,
    val description: String,
    val source: SkillSource,
    val contentHash: String,
)

/**
 * A validated Skill document. Frontmatter beyond name and description remains untrusted data:
 * none of these fields grants tools, capabilities, approval, or filesystem access.
 */
data class SkillDocument(
    val catalogEntry: SkillCatalogEntry,
    val rawContent: String,
    val body: String,
    val license: String?,
    val compatibility: String?,
    val metadata: Map<String, String>,
    val allowedTools: String?,
    val additionalFields: Map<String, Any?>,
)

data class SkillCatalogDiagnostic(
    val directoryName: String,
    val message: String,
)

data class SkillCatalog(
    val entries: List<SkillCatalogEntry>,
    val diagnostics: List<SkillCatalogDiagnostic>,
)

class InvalidSkillException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
