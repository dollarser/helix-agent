package com.helix.runtime.cli.client

import com.helix.core.model.ModelEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliEventPrefixTest {
    @Test fun prefixBindsOrderContentAndEventBoundaries() {
        val prefix = CliEventPrefix()
        prefix.append(listOf(ModelEvent.TextDelta("a")))
        prefix.append(listOf(ModelEvent.TextDelta("b")))
        val complete = listOf(ModelEvent.TextDelta("a"), ModelEvent.TextDelta("b"), ModelEvent.Completed("stop"))
        assertTrue(prefix.matches(complete))
        assertFalse(prefix.matches(listOf(ModelEvent.TextDelta("b"), ModelEvent.TextDelta("a"))))
        assertFalse(prefix.matches(listOf(ModelEvent.TextDelta("ab"))))
        assertFalse(prefix.matches(listOf(ModelEvent.TextDelta("a"))))
    }
}
