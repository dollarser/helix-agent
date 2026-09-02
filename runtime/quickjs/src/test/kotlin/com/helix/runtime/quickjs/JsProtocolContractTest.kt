package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-051 protocol closed sets (roadmap M5 / doc 03 §4): the status vocabulary, the
 * transaction-code set and the wire constants are a frozen contract — HXA-053 consumes
 * exactly these names, so they are pinned here in pure JVM.
 */
class JsProtocolContractTest {
    @Test
    fun statusClosedSetIsFrozen() {
        val expected =
            listOf(
                "SUCCESS",
                "TIMEOUT",
                "INTERRUPTED",
                "OOM",
                "JS_ERROR",
                "OUTPUT_LIMIT",
                "CRASHED",
                "CANCELLED",
                "REQUEST_REJECTED",
                "BIND_FAILED",
                "UNKNOWN",
            )
        assertEquals(expected, JsExecutionStatus.CLOSED_SET.map { it.name })
        assertEquals(1, JsExecutionStatus.entries.count { it.isSuccess })
        assertEquals(JsExecutionStatus.SUCCESS, JsExecutionStatus.entries.single { it.isSuccess })
    }

    @Test
    fun unknownWireStatusDegradesToUnknownNeverSuccess() {
        assertEquals(JsExecutionStatus.UNKNOWN, JsExecutionStatus.fromWire("BOGUS"))
        assertEquals(JsExecutionStatus.UNKNOWN, JsExecutionStatus.fromWire(null))
        assertEquals(JsExecutionStatus.UNKNOWN, JsExecutionStatus.fromWire(""))
        for (status in JsExecutionStatus.entries) {
            assertEquals(status, JsExecutionStatus.fromWire(status.name))
        }
    }

    @Test
    fun transactionCodesAreClosed() {
        assertEquals(
            setOf(
                JsProtocol.CODE_INFO,
                JsProtocol.CODE_EXECUTE,
                JsProtocol.CODE_INTERRUPT,
            ),
            JsProtocol.TRANSACTION_CODES,
        )
        // Mirrors android.os.Binder.FIRST_CALL_TRANSACTION.
        assertEquals(1, JsProtocol.FIRST_CALL_TRANSACTION)
        assertNotEquals(JsProtocol.CODE_INFO, JsProtocol.CODE_EXECUTE)
        assertNotEquals(JsProtocol.CODE_EXECUTE, JsProtocol.CODE_INTERRUPT)
        assertNotEquals(JsProtocol.CODE_INFO, JsProtocol.CODE_INTERRUPT)
    }

    @Test
    fun wireConstantsAreBounded() {
        assertEquals(1, JsProtocol.PROTOCOL_VERSION)
        assertEquals("inline parcel cap is 64 KiB", 64 * 1024, JsProtocol.PARCEL_INLINE_MAX_BYTES)
        assertTrue("detail cap must be positive", JsProtocol.MAX_DETAIL_CHARS > 0)
        assertTrue("detail cap must be bounded", JsProtocol.MAX_DETAIL_CHARS <= 8192)
        assertEquals("crash seam flag is bit 0", 1, JsProtocol.FLAG_CRASH_INJECTION)
    }

    @Test
    fun detailTruncationIsApplied() {
        val longDetail = "x".repeat(JsProtocol.MAX_DETAIL_CHARS + 100)
        val result =
            JsExecutionResult.clientFailure("exec-1", JsExecutionStatus.JS_ERROR, longDetail, "")
        assertEquals(JsProtocol.MAX_DETAIL_CHARS, result.detail.length)
    }

    @Test
    fun clientFailureMarksServiceIdentityAbsent() {
        val result = JsExecutionResult.clientFailure("exec-1", JsExecutionStatus.BIND_FAILED, "x", "")
        assertEquals(-1, result.servicePid)
        assertEquals(-1, result.serviceUid)
        assertFalse(result.status.isSuccess)
        assertEquals("exec-1", result.executionId)
        assertTrue(result.outputUtf8.isEmpty())
    }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", JsHash.sha256Utf8(""))
        assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae", JsHash.sha256Utf8("foo"))
        assertNotEquals(JsHash.sha256Utf8("a"), JsHash.sha256Utf8("b"))
    }
}
