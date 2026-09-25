package com.helix.core.storage.criteria

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ToolCallId
import com.helix.core.storage.internal.Value
import com.helix.core.storage.internal.asString
import com.helix.core.storage.internal.parseStrictArray

/**
 * Storage-level representation of a goal acceptance criterion (architecture doc 9.1:
 * `goals` persists the criteria list). `core:agent`'s `Criterion` is the domain type; this
 * storage value is what Room stores, and the recovery coordinator (HXA-015) maps between the
 * two. Evidence keeps the same rule as the domain type: a verifier plus at least one of
 * artifact reference / tool call reference.
 */
data class StoredEvidence(
    val verifier: String,
    val artifactRef: ArtifactRef?,
    val toolCallId: ToolCallId?,
) {
    init {
        require(verifier.isNotBlank() && verifier.length <= MAX_VERIFIER_LENGTH) {
            "verifier must be 1..$MAX_VERIFIER_LENGTH non-blank chars"
        }
        require(artifactRef != null || toolCallId != null) {
            "evidence must carry an artifact reference or a tool call reference"
        }
    }

    companion object {
        const val MAX_VERIFIER_LENGTH = 128
    }
}

data class StoredCriterion(
    val id: String,
    val description: String,
    val evidence: StoredEvidence?,
) {
    init {
        require(id.length in 1..MAX_ID_LENGTH && id.all { it in ID_CHARS }) {
            "criterion id must be 1..$MAX_ID_LENGTH chars of [A-Za-z0-9_-]"
        }
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_LENGTH) {
            "description must be 1..$MAX_DESCRIPTION_LENGTH non-blank chars"
        }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024
        const val MAX_CRITERIA = 32
        private val ID_CHARS: Set<Char> =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toSet()
    }
}

/**
 * Canonical storage encoding of the criteria list: a JSON array of objects with fixed field
 * order `id, description, evidence` (evidence: `verifier, artifactRef, toolCallId`; absent
 * references are `null`). Evidence presence is the only criterion-progress fact stored here.
 */
object CriteriaCodec {
    fun encode(criteria: List<StoredCriterion>): String {
        require(criteria.size <= StoredCriterion.MAX_CRITERIA) {
            "criteria list may hold at most ${StoredCriterion.MAX_CRITERIA} items"
        }
        val ids = criteria.map(StoredCriterion::id).toSet()
        require(ids.size == criteria.size) { "criterion ids must be unique" }
        return criteria
            .joinToString(separator = ",", prefix = "[", postfix = "]") { c ->
                val evidence = c.evidence
                val evidencePart =
                    if (evidence == null) {
                        "\"evidence\":null"
                    } else {
                        "\"evidence\":{" +
                            "\"verifier\":\"${escape(evidence.verifier)}\"," +
                            "\"artifactRef\":${encodeRef(evidence.artifactRef?.value)}," +
                            "\"toolCallId\":${encodeRef(evidence.toolCallId?.value)}}"
                    }
                "{\"id\":\"${escape(c.id)}\"," +
                    "\"description\":\"${escape(c.description)}\"," +
                    evidencePart +
                    "}"
            }
    }

    fun decode(text: String): List<StoredCriterion> {
        val items = parseStrictArray(text)
        require(items.size <= StoredCriterion.MAX_CRITERIA) {
            "criteria list may hold at most ${StoredCriterion.MAX_CRITERIA} items"
        }
        val seen = HashSet<String>()
        return items.map { item ->
            val criterion = parseCriterion(item)
            require(seen.add(criterion.id)) { "duplicate criterion id '${criterion.id}'" }
            criterion
        }
    }

    private fun parseCriterion(item: Value): StoredCriterion {
        val entries = (item as? Value.Obj)?.entries ?: requireNotNull(null) { "criterion must be an object" }
        val fields = listOf("id", "description", "evidence")
        require(entries.keys.toList() == fields) {
            "criterion requires id, description, evidence in that order"
        }
        val id = entries.getValue("id").asString("id")
        val description = entries.getValue("description").asString("description")
        val evidence =
            when (val evidenceValue = entries.getValue("evidence")) {
                is Value.Null -> null
                is Value.Obj -> parseEvidence(evidenceValue.entries)
                else -> requireNotNull(null) { "evidence must be an object or null" }
            }
        return StoredCriterion(id, description, evidence)
    }

    private fun parseEvidence(entries: LinkedHashMap<String, Value>): StoredEvidence {
        val fields = listOf("verifier", "artifactRef", "toolCallId")
        require(entries.keys.toList() == fields) {
            "evidence requires verifier, artifactRef, toolCallId in that order"
        }
        return StoredEvidence(
            verifier = entries.getValue("verifier").asString("verifier"),
            artifactRef = parseRef(entries.getValue("artifactRef"), "artifactRef")?.let { ArtifactRef(it) },
            toolCallId = parseRef(entries.getValue("toolCallId"), "toolCallId")?.let { ToolCallId(it) },
        )
    }

    /**
     * The reference fields are nullable STRINGS: [Value.Null] -> null, [Value.Str] ->
     * the typed id. Any other type (a tampered or corrupt criteria JSON carrying a
     * number/bool/object where a ref belongs) must FAIL, not silently shrink the
     * criterion — goal recovery treats the stored criteria as the canonical source, so
     * a silently dropped ref would change which evidence satisfies a goal.
     */
    private fun parseRef(
        value: Value,
        field: String,
    ): String? =
        when (value) {
            is Value.Null -> {
                null
            }

            is Value.Str -> {
                value.value
            }

            else -> {
                throw IllegalArgumentException(
                    "evidence field '$field' must be a string or null, was ${value::class.simpleName}",
                )
            }
        }

    private fun encodeRef(value: String?): String = if (value == null) "null" else "\"${escape(value)}\""

    internal fun escape(value: String): String =
        value
            .map { char ->
                when (char) {
                    '"' -> {
                        "\\\""
                    }

                    '\\' -> {
                        "\\\\"
                    }

                    '\n' -> {
                        "\\n"
                    }

                    '\r' -> {
                        "\\r"
                    }

                    '\t' -> {
                        "\\t"
                    }

                    '\b' -> {
                        "\\b"
                    }

                    '\u000C' -> {
                        "\\f"
                    }

                    else -> {
                        if (char < ' ') {
                            "\\u${char.code.toString(16).padStart(4, '0')}"
                        } else {
                            char.toString()
                        }
                    }
                }
            }.joinToString("")
}
