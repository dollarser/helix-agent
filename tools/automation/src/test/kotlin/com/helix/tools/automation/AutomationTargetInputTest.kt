package com.helix.tools.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationTargetInputTest {
    @Test fun packageCaseIsPreservedRatherThanRejectedOrNormalized() {
        assertEquals(setOf("com.Example.Reader", "android"), AutomationTargetInput.parse("com.Example.Reader,android"))
    }

    @Test fun commasWhitespaceAndDuplicateTargetsAreNormalized() {
        assertEquals(
            setOf("com.example.one", "com.example.two"),
            AutomationTargetInput.parse(" com.example.one，com.example.two\ncom.example.one "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTargetsHaveAnExplicitFailure() {
        AutomationTargetInput.parse(" , ， \n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneMalformedTargetRejectsTheWholeSelection() {
        AutomationTargetInput.parse("com.example.good,bad/package")
    }
}
