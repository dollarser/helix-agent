package com.helix.core.agent

import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.Hex
import com.helix.core.model.Sha256
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class ArtifactCriterionResult {
    MATCHED,
    MISMATCH,
    CONTENT_HASH_MISMATCH,
    CONTENT_TOO_LARGE,
    INVALID_UTF8,
    INCOMPATIBLE_METHOD,
}

/**
 * Checks a complete bounded snapshot, never a truncated prefix. This is only a content fact;
 * the host must separately verify the durable same-Goal source before creating any evidence.
 */
object ArtifactCriterionCheck {
    const val MAX_CONTENT_BYTES = 1_048_576

    fun verify(
        binding: CriterionVerificationBinding,
        bytes: ByteArray,
        expectedContentHash: Sha256,
    ): ArtifactCriterionResult =
        when {
            binding.method !in ARTIFACT_METHODS -> ArtifactCriterionResult.INCOMPATIBLE_METHOD
            bytes.size > MAX_CONTENT_BYTES -> ArtifactCriterionResult.CONTENT_TOO_LARGE
            else -> verifyContent(binding, bytes, expectedContentHash)
        }

    private fun verifyContent(
        binding: CriterionVerificationBinding,
        bytes: ByteArray,
        expectedContentHash: Sha256,
    ): ArtifactCriterionResult {
        val actual = Sha256(Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes)))
        return when {
            actual != expectedContentHash -> ArtifactCriterionResult.CONTENT_HASH_MISMATCH
            binding.method == CriterionVerificationMethod.ARTIFACT_SHA256 -> match(actual.hex == binding.argument)
            else -> verifyLiteral(bytes, binding.argument)
        }
    }

    private fun verifyLiteral(
        bytes: ByteArray,
        literal: String,
    ): ArtifactCriterionResult {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return ArtifactCriterionResult.INVALID_UTF8
            }
        return match(text.contains(literal))
    }

    private fun match(matches: Boolean): ArtifactCriterionResult =
        if (matches) ArtifactCriterionResult.MATCHED else ArtifactCriterionResult.MISMATCH

    private val ARTIFACT_METHODS =
        setOf(CriterionVerificationMethod.ARTIFACT_SHA256, CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS)
}
