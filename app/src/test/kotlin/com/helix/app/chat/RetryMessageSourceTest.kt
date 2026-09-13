package com.helix.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryMessageSourceTest {
    @Test fun repeatedRetriesUseTheirOriginalMessageWithoutTakingLaterInput() {
        val turns = listOf("original", "retry1", "retry2", "later")
        val users = setOf("original", "later")
        assertEquals("original", RetryMessageSource.resolve("retry2", turns, users))
        assertEquals("later", RetryMessageSource.resolve("later", turns, users))
        assertNull(RetryMessageSource.resolve("missing", turns, users))
        assertNull(RetryMessageSource.resolve("retry1", turns, emptySet()))
    }
}
