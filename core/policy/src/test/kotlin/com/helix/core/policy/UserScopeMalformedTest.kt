package com.helix.core.policy

import org.junit.Assert.assertNull
import org.junit.Test

class UserScopeMalformedTest {
    @Test fun truncatedAndExtraAutomationAndRootFieldsFailClosed() {
        val separator = "\u0001"
        listOf("a" to 4, "r" to 3).forEach { (tag, required) ->
            (0..required + 2).filter { it != required }.forEach { size ->
                val input = (listOf("hsr1", tag) + List(size) { "1" }).joinToString(separator)
                assertNull(input, UserScopeCodec.decode(input))
            }
        }
    }

    @Test fun outOfRangeTimestampsFailClosed() {
        assertNull(UserScopeCodec.decode("hsr1\u0001r\u0001${Long.MAX_VALUE}\u0001${Long.MAX_VALUE}\u0001true"))
    }
}
