package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class AccountUsageNumbersTest {
    @Test fun groupsLargeExactIntegers() {
        assertEquals("9,241,989", accountUsageAmount(BigDecimal("9241989"), Locale.US))
    }

    @Test fun marksRoundedSmallAmountsAsApproximate() {
        assertEquals("≈ 29.92", accountUsageAmount(BigDecimal("29.919967"), Locale.US))
        assertEquals("≈ 0", accountUsageAmount(BigDecimal("0.0001"), Locale.US))
    }

    @Test fun retainsExactDecimalsAndLocale() {
        assertEquals("1.25", accountUsageAmount(BigDecimal("1.25000"), Locale.US))
        assertEquals("1.234,5", accountUsageAmount(BigDecimal("1234.5"), Locale.GERMANY))
    }
}
