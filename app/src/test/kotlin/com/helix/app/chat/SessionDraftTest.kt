package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDraftTest {
    @Test fun titleNormalizesWhitespaceAndDoesNotSplitSurrogatePairs() {
        assertEquals("hello world", automaticSessionTitle("  hello\n world  "))
        assertEquals("😀".repeat(20), automaticSessionTitle("😀".repeat(25)))
        assertEquals("", automaticSessionTitle(" \n "))
    }
}
