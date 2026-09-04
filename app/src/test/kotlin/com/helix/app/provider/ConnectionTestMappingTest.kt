package com.helix.app.provider

import com.helix.app.R
import com.helix.core.model.ModelErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestMappingTest {
    @Test
    fun everyErrorCodeMapsToAStableResourceId() {
        for (code in ModelErrorCode.entries) {
            val res = ConnectionTestMapping.codeLabel(code)
            require(res != 0) { "no resource for $code" }
        }
        assertEquals(R.string.conn_error_transport, ConnectionTestMapping.codeLabel(ModelErrorCode.TRANSPORT))
        assertEquals(R.string.conn_error_auth, ConnectionTestMapping.codeLabel(ModelErrorCode.AUTH))
        assertEquals(R.string.conn_error_rate_limited, ConnectionTestMapping.codeLabel(ModelErrorCode.RATE_LIMITED))
    }

    @Test
    fun theFourProbePhasesHaveStableResourceIds() {
        assertEquals(R.string.conn_phase_1, ConnectionTestMapping.phaseLabel(1))
        assertEquals(R.string.conn_phase_2, ConnectionTestMapping.phaseLabel(2))
        assertEquals(R.string.conn_phase_3, ConnectionTestMapping.phaseLabel(3))
        assertEquals(R.string.conn_phase_4, ConnectionTestMapping.phaseLabel(4))
        assertEquals(R.string.conn_phase_unknown, ConnectionTestMapping.phaseLabel(99))
    }

    @Test
    fun failedCarriesTheStableCodeAndPhase() {
        val failed = ConnectionTestStatus.Failed(1L, phase = 3, code = ModelErrorCode.AUTH, retryable = true)
        assertEquals(ModelErrorCode.AUTH, failed.code)
        assertEquals(3, failed.phase)
        assertTrue(failed.retryable)
    }
}
