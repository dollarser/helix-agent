package com.helix.tools.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPackageNameTest {
    @Test
    fun acceptsCanonicalLowercaseAndroidPackageNames() {
        for (value in listOf("com.example.app", "org.example_2.fixture3", "single")) {
            assertTrue(value, AndroidPackageName.isValid(value))
        }
    }

    @Test
    fun rejectsEmptyUppercaseMalformedAndOversizedNames() {
        for (value in listOf("", "Com.example", "com..example", ".com.example", "1com.example", "com.example-1")) {
            assertFalse(value, AndroidPackageName.isValid(value))
        }
        assertFalse(AndroidPackageName.isValid("a".repeat(256)))
    }
}
