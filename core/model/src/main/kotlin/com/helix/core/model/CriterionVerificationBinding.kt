package com.helix.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Closed user-selected propositions; never a script or a model-selected verifier (ADR-0028). */
enum class CriterionVerificationMethod {
    ARTIFACT_SHA256,
    ARTIFACT_UTF8_CONTAINS,
    LOCAL_TOOL_SUCCESS,
    MANUAL_REVIEW,
}

/** A value is not authorization: only the host's explicit user-edit path may persist a binding. */
data class CriterionVerificationBinding(
    val method: CriterionVerificationMethod,
    val argument: String,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported criterion binding version" }
        when (method) {
            CriterionVerificationMethod.ARTIFACT_SHA256 -> {
                Sha256(argument)
            }

            CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS -> {
                require(argument.isNotBlank() && argument.length <= MAX_LITERAL_LENGTH)
                require(argument.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == argument)
            }

            CriterionVerificationMethod.LOCAL_TOOL_SUCCESS -> {
                ToolName(argument)
            }

            CriterionVerificationMethod.MANUAL_REVIEW -> {
                require(argument.isEmpty())
            }
        }
    }

    /** Length-prefixed UTF-8 prevents field-boundary ambiguity; no locale or JSON escaping dependency. */
    fun hash(
        criterionId: String,
        description: String,
    ): Sha256 {
        require(criterionId.isNotBlank() && description.isNotBlank())
        require(criterionId.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == criterionId)
        require(description.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == description)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(version)
            listOf("helix.goal.criterion", criterionId, description, method.name, argument).forEach {
                val field = it.toByteArray(Charsets.UTF_8)
                output.writeInt(field.size)
                output.write(field)
            }
        }
        return Sha256(Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())))
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_LITERAL_LENGTH = 1024
    }
}
