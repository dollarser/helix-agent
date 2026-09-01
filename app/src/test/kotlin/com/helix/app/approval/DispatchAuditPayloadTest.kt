package com.helix.app.approval

import com.helix.core.model.RiskLevel
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
