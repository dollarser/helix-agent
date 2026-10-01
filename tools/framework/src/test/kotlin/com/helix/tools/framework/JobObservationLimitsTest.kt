package com.helix.tools.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JobObservationLimitsTest {
    @Test fun observerQuotasCannotExpandBeyondTheAcceptedBoundaries() {
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(maxObservers = 17) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(maxPerSession = 5) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(maxObservers = 1, maxPerSession = 2) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(maxHandles = 9) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(maxQueries = 3) }
    }

    @Test fun queryAndWaitDurationsCannotOutgrowTheirOuterBound() {
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(maxWaitMillis = 15_001) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(queryTimeoutMillis = 2_001) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(queryTimeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { JobObservationLimits(tickMillis = 0) }
        assertEquals(1, JobObservationLimits(maxObservers = 1, maxPerSession = 1, maxQueries = 1).maxQueries)
    }
}
