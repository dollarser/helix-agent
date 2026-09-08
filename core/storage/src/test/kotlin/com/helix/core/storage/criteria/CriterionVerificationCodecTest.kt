package com.helix.core.storage.criteria

import com.helix.core.model.ArtifactRef
import com.helix.core.model.CriterionEvidenceSource
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.CriterionVerificationRecord
import com.helix.core.model.GoalId
import com.helix.core.model.GoalRunId
import com.helix.core.model.SessionId
import com.helix.core.model.Sha256
import com.helix.core.model.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CriterionVerificationCodecTest {
    private val binding = CriterionVerificationBinding(CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS, "完成\n\"ok\"")
    private val record =
        CriterionVerificationRecord(
            binding.method,
            binding.hash("c1", "查看输出"),
            CriterionEvidenceSource(GoalId("goal-1"), GoalRunId("run-2"), SessionId("session-3"), TurnId("turn-4")),
            Sha256("a".repeat(64)),
            1234,
        )
    private val criterion =
        StoredCriterion(
            "c1",
            "查看输出",
            StoredEvidence("helix.criterion.v1", ArtifactRef("artifact-5"), null, record),
            binding,
        )

    @Test
    fun sourceFingerprintRoundTripsWithoutUpgradingOlderReceipts() {
        val complete =
            criterion.copy(
                evidence =
                    requireNotNull(criterion.evidence).copy(
                        verification = record.copy(sourceHash = Sha256("b".repeat(64))),
                    ),
            )
        assertEquals(listOf(complete), CriteriaCodec.decode(CriteriaCodec.encode(listOf(complete))))
        assertNull(
            CriteriaCodec
                .decode(CriteriaCodec.encode(listOf(criterion)))
                .single()
                .evidence
                ?.verification
                ?.sourceHash,
        )
    }

    @Test
    fun roundTripKeepsBindingAndCompleteSourceWithoutLoss() {
        val encoded = CriteriaCodec.encode(listOf(criterion))
        val decoded = CriteriaCodec.decode(encoded)
        assertEquals(listOf(criterion), decoded)
        assertEquals(encoded, CriteriaCodec.encode(decoded))
    }

    @Test
    fun oldEvidenceRemainsUnboundAndHasNoVerificationRecord() {
        val old = StoredCriterion("c1", "old", StoredEvidence("old-check", ArtifactRef("artifact-1"), null))
        val decoded = CriteriaCodec.decode(CriteriaCodec.encode(listOf(old))).single()
        assertEquals(old, decoded)
        assertNull(decoded.binding)
        assertNull(decoded.evidence?.verification)
    }

    @Test
    fun userBindingWithoutEvidenceRoundTrips() {
        val pending = criterion.copy(evidence = null)
        assertEquals(listOf(pending), CriteriaCodec.decode(CriteriaCodec.encode(listOf(pending))))
    }

    @Test
    fun rejectsUnknownVersionsWithoutIntegerNarrowing() {
        val encoded = CriteriaCodec.encode(listOf(criterion))
        listOf("0", "2", "4294967297", "-1", "\"1\"").forEach { version ->
            assertThrows(IllegalArgumentException::class.java) {
                CriteriaCodec.decode(encoded.replace("\"version\":1", "\"version\":$version"))
            }
        }
    }

    @Test
    fun rejectsMissingUnknownOrWronglyTypedProofFields() {
        val encoded = CriteriaCodec.encode(listOf(criterion))
        val corruptions =
            listOf(
                encoded.replace("\"verifiedAt\":1234", "\"verifiedAt\":-1"),
                encoded.replace("\"runId\":\"run-2\",", ""),
                encoded.replace("\"runId\":\"run-2\"", "\"runId\":null"),
                encoded.replace("ARTIFACT_UTF8_CONTAINS", "SCRIPT"),
                encoded.replace("\"verifiedAt\":1234", "\"verifiedAt\":1234,\"trusted\":true"),
            )
        corruptions.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { CriteriaCodec.decode(text) }
        }
    }
}
