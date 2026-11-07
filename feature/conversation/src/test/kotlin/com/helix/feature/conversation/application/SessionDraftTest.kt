package com.helix.feature.conversation.application

import com.helix.feature.conversation.application.automaticSessionTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDraftTest {
    @Test fun titleNormalizesWhitespaceAndDoesNotSplitSurrogatePairs() {
        assertEquals("hello world", automaticSessionTitle("  hello\n world  "))
        assertEquals("😀".repeat(48), automaticSessionTitle("😀".repeat(60)))
        assertEquals("", automaticSessionTitle(" \n "))
    }
}
