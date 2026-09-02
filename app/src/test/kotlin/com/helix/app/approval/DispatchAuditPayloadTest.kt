package com.helix.app.approval

import com.helix.core.model.RiskLevel
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-036 (app): the audit redaction contract — the [StorageAuditSink.payload] allowlist and
 * the typed [DispatchAuditRecord] parse the audit page is built from. The redaction
 * invariant is structural: the event type carries no argument/output body, the payload is
 * an explicit key allowlist, and the record type has no slot for content — so the page
 * cannot render what was never stored.
 */
class DispatchAuditPayloadTest {
    private fun fullEvent() =
        DispatchAuditEvent(
            correlationId = "turn-1",
            turnId = "turn-1",
            sessionId = "sess-1",
            toolName = "fs.write",
            toolVersion = "2",
            code = DispatchOutcomeCode.SUCCESS,
            decisionSource = DecisionSource.USER,
            riskLevel = RiskLevel.L2,
            bindingHash = "a".repeat(64),
            actionFingerprint = "f".repeat(64),
            outputHash = "0".repeat(64),
            outputTruncated = true,
            startedAt = 1_000L,
            policyDecidedAt = 1_100L,
            approvalAcquiredAt = 1_200L,
            executionStartedAt = 1_300L,
            finishedAt = 1_900L,
        )

    @Test
    fun payloadContainsOnlyAllowlistedKeys() {
        val obj = Json.parseToJsonElement(StorageAuditSink.payload(fullEvent())).jsonObject
        assertEquals(StorageAuditSink.PAYLOAD_KEYS, obj.keys)
        // No key beyond the allowlist exists — bodies would need a new key, which would
        // fail this set equality (the allowlist is the only path into the column).
        assertFalse(obj.keys.any { it !in StorageAuditSink.PAYLOAD_KEYS })
    }

    @Test
    fun payloadRoundTripsThroughParseRow() {
        val record =
            StorageAuditSink.parseRow(
                id = "row-1",
                correlationId = "turn-1",
                type = StorageAuditSink.TYPE,
                actor = StorageAuditSink.ACTOR,
                redactedPayload = StorageAuditSink.payload(fullEvent()),
                timestamp = 1_000L,
            )
        assertNotNull("a well-formed tool-dispatch row must parse", record)
        val r = record!!
        assertTrue(r.complete)
        assertEquals("row-1", r.id)
        assertEquals("turn-1", r.correlationId)
        assertEquals("dispatcher", r.actor)
        assertEquals("turn-1", r.turnId)
        assertEquals("sess-1", r.sessionId)
        assertEquals("fs.write", r.toolName)
        assertEquals("2", r.toolVersion)
        assertEquals(DispatchOutcomeCode.SUCCESS, r.code)
        assertEquals(DecisionSource.USER, r.decisionSource)
        assertEquals(RiskLevel.L2, r.risk)
        assertEquals(1_000L, r.startedAt)
        assertEquals(1_900L, r.finishedAt)
    }

    @Test
    fun nullFieldsRoundTripAsNull() {
        val event =
            DispatchAuditEvent(
                correlationId = "t",
                turnId = "t",
                sessionId = "s",
                toolName = "time.now",
                toolVersion = "1",
                code = DispatchOutcomeCode.SUCCESS,
                decisionSource = DecisionSource.POLICY,
                riskLevel = null,
                bindingHash = null,
                actionFingerprint = null,
                outputHash = null,
                outputTruncated = false,
                startedAt = 10L,
                policyDecidedAt = null,
                approvalAcquiredAt = null,
                executionStartedAt = null,
                finishedAt = 20L,
            )
        val record =
            StorageAuditSink.parseRow(
                id = "r",
                correlationId = "t",
                type = StorageAuditSink.TYPE,
                actor = "dispatcher",
                redactedPayload = StorageAuditSink.payload(event),
                timestamp = 10L,
            )
        assertNotNull(record)
        assertNull(record!!.risk)
        assertNull(record.bindingHash)
        assertTrue(record.complete)
    }

    @Test
    fun queueAndAttemptMetadataAreMandatedPayloadKeys() {
        // HXA-037 (roadmap: queue/approval/execution/verification timing + attemptId 持久审计):
        // the scheduler stamps queuedAt, the dispatcher stamps attemptId per retry.
        val event = fullEvent().copy(queuedAt = 950L)
        assertEquals(1, event.attemptId)
        val obj = Json.parseToJsonElement(StorageAuditSink.payload(event)).jsonObject
        assertEquals(950L, (obj["queuedAt"] as kotlinx.serialization.json.JsonPrimitive).content.toLong())
        assertEquals(1, (obj["attemptId"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        // Without a scheduler (direct dispatch) queuedAt is null in the payload —
        // the key stays present, the shape is stable.
        val bare = Json.parseToJsonElement(StorageAuditSink.payload(fullEvent())).jsonObject
        assertTrue(bare["queuedAt"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun executionDetailIsAnAllowlistedRedactedKey() {
        // HXA-053: the QuickJS §4.8 executor metadata reaches the payload as ONE allowlisted
        // key — a nested object of hashes/sizes/limits, never a body.
        val detail =
            buildJsonObject {
                put("status", "SUCCESS")
                put("sourceSha256", "a".repeat(64))
                put("sourceBytes", 42L)
                put("inputBytes", 6L)
                put("inputSha256", "b".repeat(64))
                put("outputBytes", 13L)
                put("outputSha256", "c".repeat(64))
                put(
                    "limits",
                    buildJsonObject {
                        put("timeoutMs", 10_000L)
                        put("memoryBytes", 64L * 1024 * 1024)
                        put("maxOutputBytes", 256L * 1024)
                    },
                )
                put("isolated", true)
            }
        val event = fullEvent().copy(executionDetail = detail)
        val obj = Json.parseToJsonElement(StorageAuditSink.payload(event)).jsonObject
        // The key is present and carries the exact redacted object.
        assertEquals(detail, obj["executionDetail"]?.jsonObject)
        // The allowlist still holds: top-level keys == PAYLOAD_KEYS (executionDetail is one key).
        assertEquals(StorageAuditSink.PAYLOAD_KEYS, obj.keys)
        // A tool that reports no execution detail emits a present-but-null key (stable shape).
        val bare = Json.parseToJsonElement(StorageAuditSink.payload(fullEvent())).jsonObject
        assertTrue("an absent executionDetail stays a present null key", bare["executionDetail"] is JsonNull)
    }

    @Test
    fun rowsThatAreNotToolDispatchAreHidden() {
        assertNull(StorageAuditSink.parseRow("r", "c", "model_call", "x", "{}", 0L))
        assertNull(StorageAuditSink.parseRow("r", "c", StorageAuditSink.TYPE, "x", "not-json", 0L))
        // A JSON array is not an object payload: hidden, never rendered raw.
        assertNull(StorageAuditSink.parseRow("r", "c", StorageAuditSink.TYPE, "x", "[1,2]", 0L))
    }

    @Test
    fun malformedEnumValuesParseToNullNotRawStrings() {
        val payload =
            """{"turnId":"t","sessionId":"s","toolName":"x","toolVersion":"1","code":"NOT_A_CODE",""" +
                """"decisionSource":"ALSO_NOT","startedAt":5,"finishedAt":6}"""
        val record =
            StorageAuditSink.parseRow("r", "c", StorageAuditSink.TYPE, "d", payload, 5L)
        assertNotNull(record)
        assertNull(record!!.code)
        assertNull(record.decisionSource)
        assertFalse(record.complete)
    }

    @Test
    fun incompleteRecordsAreNotComplete() {
        // Missing sessionId/turnId: the page hides half-parsed rows (fail closed).
        val payload =
            """{"toolName":"x","toolVersion":"1","code":"SUCCESS",""" +
                """"decisionSource":"POLICY","startedAt":5,"finishedAt":6}"""
        val record =
            StorageAuditSink.parseRow("r", "c", StorageAuditSink.TYPE, "d", payload, 5L)
        assertNotNull(record)
        assertFalse(record!!.complete)
        assertNull(record.sessionId)
        assertNull(record.turnId)
    }
}
