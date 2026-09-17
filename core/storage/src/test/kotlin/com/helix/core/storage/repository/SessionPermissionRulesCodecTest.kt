package com.helix.core.storage.repository

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.storage.assertThrowsAny
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPermissionRulesCodecTest {
    @Test
    fun encodesEmptyTableAsEmptyObject() {
        assertEquals("{}", SessionPermissionRulesCodec.encode(emptyMap()))
    }

    @Test
    fun encodeIsDeterministicWithSortedKeys() {
        val rules =
            mapOf(
                OperationEffect.REMOTE_BUSINESS_MUTATION to OperationRule.ASK,
                OperationEffect.FILE_READ_WORKSPACE to OperationRule.ALLOW,
            )
        assertEquals(
            """{"FILE_READ_WORKSPACE":"ALLOW","REMOTE_BUSINESS_MUTATION":"ASK"}""",
            SessionPermissionRulesCodec.encode(rules),
        )
        assertEquals(
            SessionPermissionRulesCodec.encode(rules.toSortedMap()),
            SessionPermissionRulesCodec.encode(rules),
        )
    }

    @Test
    fun roundTripsTheFullEffectTable() {
        val rules = OperationEffect.values().associateWith { OperationRule.ASK }
        assertEquals(rules, SessionPermissionRulesCodec.decode(SessionPermissionRulesCodec.encode(rules)))
    }

    @Test
    fun decodesEmptyObjectToEmptyTable() {
        val decoded: Map<OperationEffect, OperationRule> = SessionPermissionRulesCodec.decode("{}")
        assertTrue("empty object must decode to an empty table", decoded.isEmpty())
    }

    @Test
    fun decodeRejectsMalformedInput() {
        assertThrowsAny("a bare quoted rule") { SessionPermissionRulesCodec.decode("\"ALLOW\"") }
        assertThrowsAny("a stray bare key") { SessionPermissionRulesCodec.decode("{FILE_READ_WORKSPACE}") }
        assertThrowsAny("a missing colon") { SessionPermissionRulesCodec.decode("""{"ALLOW"}""") }
        assertThrowsAny("trailing junk") {
            SessionPermissionRulesCodec.decode("""{"ALLOW":"X"}garbage""")
        }
    }

    @Test
    fun decodeRejectsUnknownEffectAndUnknownRule() {
        assertThrowsAny("an unknown effect") {
            SessionPermissionRulesCodec.decode("""{"NOT_AN_EFFECT":"ASK"}""")
        }
        assertThrowsAny("an unknown rule") {
            SessionPermissionRulesCodec.decode("""{"FILE_READ_WORKSPACE":"MAYBE"}""")
        }
    }

    @Test
    fun decodeRejectsDuplicateEffectKeys() {
        assertThrowsAny("a duplicated effect key") {
            SessionPermissionRulesCodec.decode(
                """{"FILE_READ_WORKSPACE":"ASK","FILE_READ_WORKSPACE":"ALLOW"}""",
            )
        }
    }
}
