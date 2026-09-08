package com.helix.core.storage.criteria

import com.helix.core.model.CriterionPendingReview
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingCriterionReviewCodecTest {
    private val binding = CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, "")
    private val review =
        CriterionPendingReview(ToolCallId("call"), binding.hash("c1", "Review"), Sha256("a".repeat(64)), null, 100)
    private val criterion = StoredCriterion("c1", "Review", null, binding, review)

    @Test
    fun pendingSelectionRoundTripsWithoutBecomingEvidence() {
        val restored = CriteriaCodec.decode(CriteriaCodec.encode(listOf(criterion))).single()
        assertEquals(criterion, restored)
        assertFalse(restored.satisfied)
        assertNull(restored.evidence)
    }

    @Test
    fun changedBindingCannotCarryOldPendingReview() {
        assertThrows(IllegalArgumentException::class.java) { criterion.copy(description = "changed") }
        assertThrows(IllegalArgumentException::class.java) { criterion.copy(binding = null) }
    }

    @Test
    fun rejectsUnknownPendingVersionAndMalformedReference() {
        val encoded = CriteriaCodec.encode(listOf(criterion))
        assertThrows(IllegalArgumentException::class.java) {
            CriteriaCodec.decode(
                encoded.replace("\"pendingReview\":{\"version\":1", "\"pendingReview\":{\"version\":2"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CriteriaCodec.decode(encoded.replace("\"artifactRef\":null", "\"artifactRef\":true"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CriteriaCodec.decode(encoded.replace("\"reviewedAt\":100", "\"reviewedAt\":-1"))
        }
    }
}
