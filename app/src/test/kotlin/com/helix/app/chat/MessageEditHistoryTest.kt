package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageEditHistoryTest {
    @Test fun overlappingEditsMergeButLaterRepliesAreNotMarkedStale() {
        val ranges = MessageEditHistory.merge(listOf(2L..8L, 14L..16L), 5L..12L)
        assertEquals(listOf(2L..12L, 14L..16L), ranges)
        assertFalse(ranges.any { 20L in it })
        assertEquals(ranges, MessageEditHistory.decode(MessageEditHistory.encode(ranges)))
    }

    @Test fun branchKeepsOnlyCopiedRepliesAndRemapsTheirSequence() {
        assertEquals(listOf(0L..1L), MessageEditHistory.remap(listOf(3L..12L), mapOf(4L to 0L, 8L to 1L, 20L to 2L)))
        assertEquals(emptyList<LongRange>(), MessageEditHistory.remap(listOf(3L..12L), mapOf(20L to 0L)))
    }

    @Test fun malformedAnnotationsAreRejectedInsteadOfBecomingCurrentHistory() {
        for (value in listOf("{}", "[[2,1]]", "[[-1,2]]", "[[1]]", "[[1,2,3]]")) {
            assertThrows(IllegalArgumentException::class.java) { MessageEditHistory.decode(value) }
        }
    }
}
