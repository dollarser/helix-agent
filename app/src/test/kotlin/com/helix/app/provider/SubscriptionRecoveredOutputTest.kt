package com.helix.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRecoveredOutputTest {
    @Test fun emptyReplyHasNoSyntheticText() {
        assertTrue(SubscriptionRecoveredOutput.fromText("").pages.isEmpty())
    }

    @Test fun emojiAtPageBoundaryIsNotSplitOrLost() {
        val text = "中".repeat(4095) + "😀" + "后文".repeat(3000)
        val pages = SubscriptionRecoveredOutput.fromText(text).pages
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= 4096 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
}
