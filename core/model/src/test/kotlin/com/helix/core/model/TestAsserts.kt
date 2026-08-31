package com.helix.core.model

import org.junit.Assert.fail

/**
 * Reified `assertThrows` for JUnit4 (the Kotlin extension is provided by kotlin-test, which
 * this module does not depend on). Mirrors kotlin.test semantics: returns the thrown instance.
 */
internal inline fun <reified T : Throwable> assertThrows(
    message: String? = null,
    block: () -> Unit,
): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        val detail = if (message.isNullOrBlank()) "" else "$message: "
        fail("${detail}expected ${T::class.java.simpleName} but got ${t::class.java.name}: ${t.message}")
    }
    val detail = if (message.isNullOrBlank()) "" else "$message: "
    fail("${detail}expected ${T::class.java.simpleName} but nothing was thrown")
    throw AssertionError("unreachable")
}
