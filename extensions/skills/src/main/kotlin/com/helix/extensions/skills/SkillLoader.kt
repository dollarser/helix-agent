package com.helix.extensions.skills

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.schema.JsonSchema
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.Normalizer

@Suppress("TooManyFunctions")
class SkillLoader {
    fun load(
        skillDirectory: Path,
        source: SkillSource,
    ): SkillDocument = load(skillDirectory, source, requireDirectoryNameMatch = true)

    internal fun loadUnmatchedDirectory(
        skillDirectory: Path,
        source: SkillSource,
    ): SkillDocument = load(skillDirectory, source, requireDirectoryNameMatch = false)

    private fun load(
        skillDirectory: Path,
        source: SkillSource,
        requireDirectoryNameMatch: Boolean,
    ): SkillDocument {
        requireSafeDirectory(skillDirectory)
        val skillFile = skillDirectory.resolve(SKILL_FILE_NAME)
        if (!Files.isRegularFile(skillFile) || Files.isSymbolicLink(skillFile)) {
            invalid("Skill directory must contain a regular, non-symlink SKILL.md")
        }

        val bytes =
            try {
                readBounded(skillFile)
            } catch (failure: InvalidSkillException) {
                throw failure
            } catch (failure: IOException) {
                invalid("Unable to read SKILL.md", failure)
            }
        return parse(bytes, skillDirectory.fileName.toString(), source, requireDirectoryNameMatch)
    }

    internal fun loadBuiltIn(
        content: String,
        name: String,
    ): SkillDocument = parse(content.toByteArray(Charsets.UTF_8), name, SkillSource.BUILT_IN, true)

    private fun parse(
        bytes: ByteArray,
        directoryName: String,
        source: SkillSource,
        requireDirectoryNameMatch: Boolean,
    ): SkillDocument {
        val content = decodeUtf8(bytes)
        if ('\u0000' in content) {
            invalid("SKILL.md must not contain NUL characters")
        }
        val sections = splitFrontmatter(content)
        val frontmatter = parseFrontmatter(sections.frontmatter)
        val name = requiredString(frontmatter, "name")
        validateName(name, directoryName, requireDirectoryNameMatch)
        val description = requiredString(frontmatter, "description")
        if (description.isBlank() || description.length > MAX_DESCRIPTION_LENGTH) {
            invalid("description must contain 1..$MAX_DESCRIPTION_LENGTH characters")
        }

        val license = optionalString(frontmatter, "license", MAX_LICENSE_LENGTH)
        val compatibility = optionalString(frontmatter, "compatibility", MAX_COMPATIBILITY_LENGTH, nonBlank = true)
        val allowedTools = optionalString(frontmatter, "allowed-tools", MAX_ALLOWED_TOOLS_LENGTH)
        val metadata = optionalMap(frontmatter, "metadata")
        val knownFields = setOf("name", "description", "license", "compatibility", "metadata", "allowed-tools")
        val additionalFields = frontmatter.filterKeys { it !in knownFields }

        return SkillDocument(
            catalogEntry =
                SkillCatalogEntry(
                    name = name,
                    description = description,
                    source = source,
                    contentHash = sha256(bytes),
                ),
            rawContent = content,
            body = sections.body,
            license = license,
            compatibility = compatibility,
            metadata = metadata,
            allowedTools = allowedTools,
            additionalFields = additionalFields,
        )
    }

    private fun requireSafeDirectory(skillDirectory: Path) {
        if (!Files.isDirectory(skillDirectory) || Files.isSymbolicLink(skillDirectory)) {
            invalid("Skill path must be a regular, non-symlink directory")
        }
    }

    private fun readBounded(skillFile: Path): ByteArray {
        val size = Files.size(skillFile)
        if (size > MAX_SKILL_BYTES) {
            invalid("SKILL.md exceeds the $MAX_SKILL_BYTES byte limit")
        }
        return Files.readAllBytes(skillFile)
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            invalid("SKILL.md must be valid UTF-8", failure)
        }

    private fun splitFrontmatter(content: String): Sections {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) {
            invalid("SKILL.md must start with YAML frontmatter")
        }
        val closingStart = normalized.indexOf("\n---\n", startIndex = 4)
        val closingAtEof = normalized.endsWith("\n---")
        if (closingStart < 0 && !closingAtEof) {
            invalid("SKILL.md frontmatter is not closed")
        }
        val end = if (closingStart >= 0) closingStart else normalized.length - 4
        if (end - 4 > MAX_FRONTMATTER_CHARS) {
            invalid("SKILL.md frontmatter exceeds the $MAX_FRONTMATTER_CHARS character limit")
        }
        val bodyStart = if (closingStart >= 0) closingStart + 5 else normalized.length
        return Sections(
            frontmatter = normalized.substring(4, end),
            body = normalized.substring(bodyStart),
        )
    }

    private fun parseFrontmatter(source: String): Map<String, Any?> {
        val settings =
            LoadSettings
                .builder()
                .setLabel(SKILL_FILE_NAME)
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(0)
                .setCodePointLimit(MAX_FRONTMATTER_CHARS)
                .setSchema(JsonSchema())
                .build()
        val loaded =
            try {
                Load(settings).loadFromString(source)
            } catch (failure: YamlEngineException) {
                invalid("Invalid YAML frontmatter", failure)
            }
        if (loaded !is Map<*, *>) {
            invalid("YAML frontmatter must be a mapping")
        }
        if (loaded.size > MAX_TOP_LEVEL_FIELDS) {
            invalid("YAML frontmatter has too many fields")
        }
        val result = linkedMapOf<String, Any?>()
        loaded.forEach { (rawKey, rawValue) ->
            val key = rawKey as? String ?: invalid("Frontmatter keys must be strings")
            if (key.length > MAX_FIELD_KEY_LENGTH) {
                invalid("Frontmatter key exceeds the length limit")
            }
            result[key] = freezeValue(rawValue, depth = 0)
        }
        return result
    }

    private fun freezeValue(
        value: Any?,
        depth: Int,
    ): Any? {
        if (depth > MAX_VALUE_DEPTH) {
            invalid("Frontmatter value nesting exceeds the limit")
        }
        return when (value) {
            null, is String, is Boolean, is Number -> {
                value
            }

            is List<*> -> {
                if (value.size > MAX_COLLECTION_ENTRIES) {
                    invalid("Frontmatter list exceeds the entry limit")
                }
                value.map { freezeValue(it, depth + 1) }
            }

            is Map<*, *> -> {
                if (value.size > MAX_COLLECTION_ENTRIES) {
                    invalid("Frontmatter mapping exceeds the entry limit")
                }
                value.entries.associate { (rawKey, rawValue) ->
                    val key =
                        rawKey as? String
                            ?: invalid("Nested frontmatter keys must be strings")
                    if (key.length > MAX_FIELD_KEY_LENGTH) {
                        invalid("Nested frontmatter key exceeds the length limit")
                    }
                    key to freezeValue(rawValue, depth + 1)
                }
            }

            else -> {
                invalid("Unsupported YAML value type: ${value.javaClass.simpleName}")
            }
        }
    }

    private fun requiredString(
        frontmatter: Map<String, Any?>,
        key: String,
    ): String =
        frontmatter[key] as? String
            ?: invalid("$key is required and must be a string")

    private fun optionalString(
        frontmatter: Map<String, Any?>,
        key: String,
        maxLength: Int,
        nonBlank: Boolean = false,
    ): String? {
        val value = frontmatter[key] ?: return null
        if (value !is String) {
            invalid("$key must be a string")
        }
        if (value.length > maxLength) {
            invalid("$key exceeds the $maxLength character limit")
        }
        if (nonBlank && value.isBlank()) {
            invalid("$key must not be blank")
        }
        return value
    }

    private fun optionalMap(
        frontmatter: Map<String, Any?>,
        key: String,
    ): Map<String, String> {
        val value = frontmatter[key] ?: return emptyMap()
        if (value !is Map<*, *>) {
            invalid("$key must be a mapping")
        }
        return value.entries.associate { (rawKey, rawValue) ->
            val mapKey =
                rawKey as? String
                    ?: invalid("$key keys must be strings")
            val mapValue =
                rawValue as? String
                    ?: invalid("$key values must be strings")
            mapKey to mapValue
        }
    }

    private fun validateName(
        name: String,
        directoryName: String,
        requireDirectoryNameMatch: Boolean,
    ) {
        val normalizedName = Normalizer.normalize(name, Normalizer.Form.NFKC)
        val normalizedDirectory = Normalizer.normalize(directoryName, Normalizer.Form.NFKC)
        if (name != normalizedName || !NAME_PATTERN.matches(name) || "--" in name) {
            invalid(
                "name must be 1..$MAX_NAME_LENGTH ASCII lowercase letters, digits, or single hyphens",
            )
        }
        if (requireDirectoryNameMatch && normalizedName != normalizedDirectory) {
            invalid("name must match the parent directory name")
        }
    }

    private fun invalid(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw InvalidSkillException(message, cause)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Sections(
        val frontmatter: String,
        val body: String,
    )

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_BYTES = 1_048_576L
        const val MAX_FRONTMATTER_CHARS = 65_536
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1_024
        const val MAX_COMPATIBILITY_LENGTH = 500
        const val MAX_LICENSE_LENGTH = 1_024
        const val MAX_ALLOWED_TOOLS_LENGTH = 4_096
        private const val MAX_TOP_LEVEL_FIELDS = 128
        private const val MAX_COLLECTION_ENTRIES = 256
        private const val MAX_FIELD_KEY_LENGTH = 256
        private const val MAX_VALUE_DEPTH = 8
        private val NAME_PATTERN = Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")
    }
}
