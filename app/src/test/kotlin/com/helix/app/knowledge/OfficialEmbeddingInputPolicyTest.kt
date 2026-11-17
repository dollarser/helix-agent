package com.helix.app.knowledge

import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialEmbeddingInputPolicyTest {
    private val small = "text-embedding-3-small"

    @Test fun acceptsExactPerInputAndBatchUtf8Boundaries() {
        validateOfficialEmbeddingInputs(small, List(4) { "a".repeat(8192) })
        validateOfficialEmbeddingInputs(small, listOf("中".repeat(2730) + "ab"))
        validateOfficialEmbeddingInputs(small, listOf("😀".repeat(2048)))
    }

    @Test fun rejectsOneByteOverForAsciiChineseEmojiAndBatch() {
        for (inputs in listOf(
            listOf("a".repeat(8193)),
            listOf("中".repeat(2731)),
            listOf("😀".repeat(2048) + "a"),
            List(4) { "a".repeat(8192) } + "a",
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateOfficialEmbeddingInputs(small, inputs) }
        }
    }

    @Test fun doesNotApplySmallGatewayLimitsToLocalOrOtherModels() {
        for (model in listOf("Qwen/Qwen3-Embedding-0.6B", "text-embedding-3-large", "synthetic")) {
            validateOfficialEmbeddingInputs(model, listOf("中".repeat(8192)))
        }
    }

    @Test fun rejectsEmptyAndOversizedBatchWithoutEchoingContents() {
        for (inputs in listOf(emptyList(), listOf(" "), List(33) { "x" })) {
            assertThrows(IllegalArgumentException::class.java) { validateOfficialEmbeddingInputs(small, inputs) }
        }
    }
}
