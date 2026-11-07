package com.helix.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Shared JUnit4 failure helper for the storage unit tests. */
internal fun assertThrows(
    message: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        assertTrue("unexpected exception message: ${e.message}", e.message != null)
        return
    }
    fail("$message: expected IllegalArgumentException, nothing thrown")
}

internal fun assertThrowsAny(
    message: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Exception) {
        if (e is AssertionError) throw e
        return
    }
    fail("$message: expected an exception, nothing thrown")
}

internal fun assertEqualsMessage(
    message: String,
    block: () -> Unit,
) {
    try {
        block()
        fail("$message: expected IllegalArgumentException, nothing thrown")
    } catch (e: IllegalArgumentException) {
        assertEquals(message, e.message)
    }
}
