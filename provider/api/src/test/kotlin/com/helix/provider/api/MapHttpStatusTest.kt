package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.wire.mapHttpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MapHttpStatusTest {
    @Test
    fun authFamilyMapsToAuthNotRetryable() {
        assertEquals(ModelErrorCode.AUTH to false, mapHttpStatus(401))
        assertEquals(ModelErrorCode.AUTH to false, mapHttpStatus(403))
    }

    @Test
    fun gatewayTimeoutMapsToTimeoutRetryable() {
        assertEquals(ModelErrorCode.TIMEOUT to true, mapHttpStatus(408))
    }

    @Test
    fun throttlingMapsToRateLimitedRetryable() {
        assertEquals(ModelErrorCode.RATE_LIMITED to true, mapHttpStatus(429))
    }

    @Test
    fun transientServerErrorsAreRetryable() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals("status $status", ModelErrorCode.SERVER_ERROR to true, mapHttpStatus(status))
        }
    }

    @Test
    fun nonTransientServerErrorsAreNotRetryable() {
        for (status in listOf(501, 505, 599)) {
            assertEquals("status $status", ModelErrorCode.SERVER_ERROR to false, mapHttpStatus(status))
        }
    }

    @Test
    fun otherClientErrorsMapToProtocolNotRetryable() {
        for (status in listOf(400, 404, 410, 413, 418, 422, 451)) {
            assertEquals("status $status", ModelErrorCode.PROTOCOL to false, mapHttpStatus(status))
        }
    }

    @Test
    fun unknownStatusFailsClosedAsServerError() {
        assertEquals(ModelErrorCode.SERVER_ERROR to false, mapHttpStatus(100))
        assertEquals(ModelErrorCode.SERVER_ERROR to false, mapHttpStatus(301))
        assertEquals(ModelErrorCode.SERVER_ERROR to false, mapHttpStatus(999))
    }

    @Test
    fun successStatusIsNotAFailure() {
        assertThrows(IllegalStateException::class.java) { mapHttpStatus(200) }
        assertThrows(IllegalStateException::class.java) { mapHttpStatus(204) }
    }
}
