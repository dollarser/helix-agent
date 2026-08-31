package com.helix.core.storage.criteria

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ToolCallId
import com.helix.core.storage.internal.Value
import com.helix.core.storage.internal.asBool
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

    val satisfied: Boolean
        get() = evidence != null

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
 * order `id, description, satisfied, evidence` (evidence: `verifier, artifactRef, toolCallId`;
 * absent references are `null`), same strict rules as the ADR-0001 subset. `satisfied` is
 * derived: true iff evidence is present.
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
                            "\"toolCallId\":${encodeRef(evidence.toolCallId?.value)}" +
                            "}"
                    }
                "{\"id\":\"${escape(c.id)}\"," +
                    "\"description\":\"${escape(c.description)}\"," +
                    "\"satisfied\":${c.satisfied}," +
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
        require(entries.keys.toList() == listOf("id", "description", "satisfied", "evidence")) {
            "criterion requires id, description, satisfied, evidence in that order"
        }
        val id = entries.getValue("id").asString("id")
        val description = entries.getValue("description").asString("description")
        val satisfied = entries.getValue("satisfied").asBool("satisfied")
        val evidence =
            when (val evidenceValue = entries.getValue("evidence")) {
                is Value.Null -> null
                is Value.Obj -> parseEvidence(evidenceValue.entries)
                else -> requireNotNull(null) { "evidence must be an object or null" }
            }
        val criterion = StoredCriterion(id, description, evidence)
        require(criterion.satisfied == satisfied) { "criterion '$id' satisfied flag disagrees with evidence" }
        return criterion
    }

    private fun parseEvidence(entries: LinkedHashMap<String, Value>): StoredEvidence {
        require(entries.keys.toList() == listOf("verifier", "artifactRef", "toolCallId")) {
            "evidence requires verifier, artifactRef, toolCallId in that order"
        }
        return StoredEvidence(
            verifier = entries.getValue("verifier").asString("verifier"),
            artifactRef = (entries.getValue("artifactRef") as? Value.Str)?.value?.let { ArtifactRef(it) },
            toolCallId = (entries.getValue("toolCallId") as? Value.Str)?.value?.let { ToolCallId(it) },
        )
    }

    private fun encodeRef(value: String?): String = if (value == null) "null" else "\"${escape(value)}\""

    private fun escape(value: String): String =
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
