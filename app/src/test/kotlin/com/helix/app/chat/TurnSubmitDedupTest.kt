package com.helix.app.chat

import com.helix.core.model.TurnState
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.MessageAttachmentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the PERSISTENT submit-dedup logic (research doc section 34; HX2-01 §2e): the
 * [TurnInputFingerprint] binds a client-request id to the exact content of a submission, and
 * [TurnDedup] decides how a re-driven id resolves against its receipt row — a same session + input
 * dedups to the started turn; any divergence is a conflict (fail-closed). Pure, so they run on the
 * JVM where the heavy [ChatService] cannot be constructed.
 */
class TurnSubmitDedupTest {
    private fun binding(
        artifactId: String,
        sha: String = "sha-$artifactId",
    ) = MessageAttachmentRepository.Binding(artifactId, "INPUT", sha)

    private fun turn(
        id: String,
        sessionId: String,
        inputFingerprint: String,
    ) = TurnEntity(
        id = id,
        sessionId = sessionId,
        state = TurnState.CREATED.name,
        stepCount = 0,
        startedAt = 0,
        endedAt = null,
        errorCode = null,
        clientRequestId = "req-1",
        inputFingerprint = inputFingerprint,
    )

    // --- TurnInputFingerprint ---

    @Test
    fun theFingerprintIsStableForIdenticalContent() {
        val input = listOf(binding("art-1"), binding("art-2"))
        assertEquals(TurnInputFingerprint.of("hello", input), TurnInputFingerprint.of("hello", input))
    }

    @Test
    fun aDifferentTextChangesTheFingerprint() {
        assertNotEquals(TurnInputFingerprint.of("hello", emptyList()), TurnInputFingerprint.of("world", emptyList()))
    }

    @Test
    fun aDifferentAttachmentChangesTheFingerprint() {
        assertNotEquals(
            TurnInputFingerprint.of("x", listOf(binding("art-1"))),
            TurnInputFingerprint.of("x", listOf(binding("art-2"))),
        )
    }

    @Test
    fun aDifferentAttachmentOrderChangesTheFingerprint() {
        // The binding order is the message ordinal; a re-drive must preserve it, so a reorder is a
        // distinct content and must NOT collide with the original fingerprint.
        assertNotEquals(
            TurnInputFingerprint.of("x", listOf(binding("art-1"), binding("art-2"))),
            TurnInputFingerprint.of("x", listOf(binding("art-2"), binding("art-1"))),
        )
    }

    @Test
    fun theFieldSeparatorPreventsAConcatenationCollision() {
        // Without a separator, text "ab" + artifact "c" would hash like text "a" + artifact "bc".
        val a = TurnInputFingerprint.of("ab", listOf(binding("c")))
        val b = TurnInputFingerprint.of("a", listOf(binding("bc")))
        assertNotEquals(a, b)
    }

    @Test
    fun aNullTextFingerprintsLikeTheEmptyText() {
        assertEquals(TurnInputFingerprint.of(null, emptyList()), TurnInputFingerprint.of("", emptyList()))
    }

    @Test
    fun theFingerprintIsA64CharLowercaseSha256Hex() {
        val fp = TurnInputFingerprint.of("hello", listOf(binding("art-1")))
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // --- TurnDedup ---

    @Test
    fun anUnknownClientRequestIdIsFresh() {
        assertEquals(TurnDedupDecision.Fresh, TurnDedup.decide(null, "s1", "fp"))
    }

    @Test
    fun aSameSessionAndInputReDriveDedupsToTheStartedTurn() {
        val fp = TurnInputFingerprint.of("hello", emptyList())
        val decision = TurnDedup.decide(turn("t1", "s1", fp), "s1", fp)
        assertEquals(TurnDedupDecision.Dedup("t1"), decision)
    }

    @Test
    fun aDifferentInputUnderTheSameIdIsAConflict() {
        val fp = TurnInputFingerprint.of("hello", emptyList())
        val other = TurnInputFingerprint.of("different content", emptyList())
        assertEquals(TurnDedupDecision.Conflict, TurnDedup.decide(turn("t1", "s1", fp), "s1", other))
    }

    @Test
    fun aDifferentSessionUnderTheSameIdIsAConflict() {
        val fp = TurnInputFingerprint.of("hello", emptyList())
        assertEquals(TurnDedupDecision.Conflict, TurnDedup.decide(turn("t1", "s1", fp), "s2", fp))
    }

    @Test
    fun aDifferentSessionAndInputIsAConflict() {
        val fp = TurnInputFingerprint.of("hello", emptyList())
        val other = TurnInputFingerprint.of("other", emptyList())
        assertEquals(TurnDedupDecision.Conflict, TurnDedup.decide(turn("t1", "s1", fp), "s2", other))
    }
}
