package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDraftTest {
    @Test fun titleNormalizesWhitespaceAndDoesNotSplitSurrogatePairs() {
        assertEquals("hello world", automaticSessionTitle("  hello\n world  "))
        assertEquals("😀".repeat(48), automaticSessionTitle("😀".repeat(60)))
        assertEquals("", automaticSessionTitle(" \n "))
    }
}
