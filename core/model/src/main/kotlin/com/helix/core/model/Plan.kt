package com.helix.core.model

import com.helix.core.model.internal.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val MAX_TEXT_LENGTH = 1024
private const val MAX_TITLE_LENGTH = 256
private const val MAX_STEPS = 64
private const val MAX_LIST_ITEMS = 32

private fun requireBoundedText(
    name: String,
    value: String,
    maxLength: Int,
) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= maxLength) { "$name must be <= $maxLength characters" }
}

private fun requireBoundedStringList(
    name: String,
    values: List<String>,
) {
    require(values.size <= MAX_LIST_ITEMS) { "$name must have <= $MAX_LIST_ITEMS items" }
    values.forEachIndexed { index, value -> requireBoundedText("$name[$index]", value, MAX_TEXT_LENGTH) }
}

/**
 * One step of a versioned [PlanArtifact] (modes doc section 6.1). Plan mode is read-only
 * research that produces a versioned artifact; the artifact is not free-form plan text.
 */
data class PlanStep(
    val title: String,
    val description: String,
) {
    init {
        requireBoundedText("title", title, MAX_TITLE_LENGTH)
        requireBoundedText("description", description, MAX_TEXT_LENGTH)
    }
}

/**
 * Versioned plan artifact produced by Plan mode (modes doc section 6.1).
 *
 * `Plan` is not "the model writes a plan paragraph": the result is this structured artifact.
 * After Plan mode completes, only an explicit user choice creates an Act run or Goal, and the
 * artifact's [sha256] is written into that run record - so the hash is computed over the full
 * canonical form including [version]: revising the plan ([withNextVersion]) changes the hash.
 *
 * Storage encoding follows ADR-0001 (strict canonical JSON, fixed field order); the `plans` /
 * `plan_steps` Room tables (architecture doc section 9.1, implemented in HXA-014) persist it.
 */
data class PlanArtifact(
    val id: PlanId,
    val objective: String,
    val assumptions: List<String>,
    val steps: List<PlanStep>,
    val acceptanceCriteria: List<String>,
    val risks: List<String>,
    val version: Int,
) {
    init {
        requireBoundedText("objective", objective, MAX_TEXT_LENGTH)
        require(steps.isNotEmpty()) { "steps must not be empty" }
        require(steps.size <= MAX_STEPS) { "steps must have <= $MAX_STEPS items" }
        require(acceptanceCriteria.isNotEmpty()) { "acceptanceCriteria must not be empty" }
        requireBoundedStringList("assumptions", assumptions)
        requireBoundedStringList("acceptanceCriteria", acceptanceCriteria)
        requireBoundedStringList("risks", risks)
        require(version >= 1) { "version must be >= 1" }
    }

    /** Canonical JSON encoding with the fixed field order of ADR-0001. */
    fun toStorageString(): String {
        val stepBodies =
            steps.map { step ->
                Json.objectBody(
                    listOf(
                        "title" to Json.string(step.title),
                        "description" to Json.string(step.description),
                    ),
                )
            }
        val pairs =
            listOf(
                "id" to Json.string(id.toString()),
                "objective" to Json.string(objective),
                "assumptions" to Json.array(assumptions.map(Json::string)),
                "steps" to Json.array(stepBodies),
                "acceptanceCriteria" to Json.array(acceptanceCriteria.map(Json::string)),
                "risks" to Json.array(risks.map(Json::string)),
                "version" to Json.long(version.toLong()),
            )
        return Json.objectBody(pairs)
    }

    /** Deterministic SHA-256 over [toStorageString]; identifies this exact plan version. */
    fun sha256(): Sha256 = Sha256(MessageDigest.getInstance("SHA-256").digest(storageBytes()).toHexString())

    private fun storageBytes(): ByteArray = toStorageString().toByteArray(StandardCharsets.UTF_8)

    /** Returns this plan revised as the next version; the hash changes accordingly. */
    fun withNextVersion(): PlanArtifact = copy(version = version + 1)
}

private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
