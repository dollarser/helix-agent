package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegedUiServiceAccessTest {
    @Test fun aMissingWindowServiceRefusesBeforeAnyConnectionCanBeCreated() {
        val queried = mutableListOf<String>()
        assertThrows(PrivilegedUiServiceUnavailable::class.java) {
            requirePrivilegedUiServices {
                queried += it
                false
            }
        }
        assertEquals(listOf("window"), queried)
    }

    @Test fun bothRequiredServicesMustBeAccessible() {
        assertThrows(PrivilegedUiServiceUnavailable::class.java) {
            requirePrivilegedUiServices { it == "window" }
        }
        requirePrivilegedUiServices { true }
    }

    @Test fun unexpectedLookupFailuresAreNeverReclassifiedAsSafeRefusals() {
        assertThrows(SecurityException::class.java) {
            requirePrivilegedUiServices { throw SecurityException("lookup failed") }
        }
    }
}
