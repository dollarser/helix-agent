package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

/**
 * HXA-051 instance-name rules (roadmap M5): `js_` + 32 lowercase hex, deterministic per
 * execution ID, only Android-allowed characters, bounded length, collision-resistant.
 */
class JsInstanceNameTest {
    @Test
    fun nameHasExactFixedForm() {
        val name = JsInstanceName.forExecution("execution-42")
        assertEquals("total length must be 35", JsInstanceName.TOTAL_LENGTH, name.length)
        assertEquals("prefix must be js_", "js_", name.substring(0, 3))
        assertTrue(
            "must match js_ + 32 lowercase hex, got $name",
            name.matches(Regex("js_[0-9a-f]{32}")),
        )
        assertTrue(JsInstanceName.isValid(name))
    }

    @Test
    fun nameContainsOnlyAndroidAllowedCharacters() {
        // The instance name becomes part of the isolated process label; Android rejects
        // most punctuation in process names. Derivation must guarantee [a-z0-9_] only.
        val name = JsInstanceName.forExecution("any execution id")
        assertTrue(
            "only [a-z0-9_] allowed, got $name",
            name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' },
        )
    }

    @Test
    fun derivationIsStableForHostileIds() {
        // IDs containing Android-illegal characters (dot, slash, spaces, uppercase, CJK,
        // NUL, emoji) must still derive a fully valid name — the derivation, not the ID,
        // is what is bound.
        val hostile =
            listOf(
                "a.b/c d",
                "UPPER",
                "中 文 id",
                "nul\u0000inside",
                "emoji-\uD83D\uDE80-id",
                "a",
                "",
            )
        for (id in hostile) {
            val name = JsInstanceName.forExecution(id)
            assertTrue("invalid derivation for id ${id.escapeDump()}: $name", JsInstanceName.isValid(name))
            assertEquals(JsInstanceName.TOTAL_LENGTH, name.length)
        }
    }

    @Test
    fun derivationIsDeterministic() {
        val id = "the-same-execution-id"
        assertEquals(JsInstanceName.forExecution(id), JsInstanceName.forExecution(id))
    }

    @Test
    fun differentIdsDoNotCollideInRandomBatch() {
        val random = SecureRandom()
        val names = mutableSetOf<String>()
        repeat(1000) { i ->
            val id = if (i % 2 == 0) UUID.randomUUID().toString() else random.nextLong().toString(16)
            val name = JsInstanceName.forExecution(id)
            assertTrue("collision for id $id", names.add(name))
        }
    }

    @Test
    fun nearIdenticalIdsDoNotCollide() {
        val pairs =
            listOf(
                "a" to "a.",
                "a" to "a ",
                "a" to "A",
                "execution-1" to "execution-01",
                "execution-1" to "execution-2",
                "exec_1" to "exec1",
                "js_00000000000000000000000000000000" to "js_00000000000000000000000000000001",
            )
        for ((left, right) in pairs) {
            assertNotEquals(
                "near-collision between $left and $right",
                JsInstanceName.forExecution(left),
                JsInstanceName.forExecution(right),
            )
        }
    }

    @Test
    fun validatorRejectsMalformedNames() {
        val valid = JsInstanceName.forExecution("x")
        assertFalse("uppercase hex must be rejected", JsInstanceName.isValid(valid.uppercase()))
        assertFalse("missing prefix must be rejected", JsInstanceName.isValid(valid.drop(1)))
        assertFalse("short name must be rejected", JsInstanceName.isValid("js_abcd"))
        assertFalse("long name must be rejected", JsInstanceName.isValid(valid + "a"))
        assertFalse("dot must be rejected", JsInstanceName.isValid("js_a.000000000000000000000000000"))
        assertFalse("space must be rejected", JsInstanceName.isValid("js_ 0000000000000000000000000000"))
        assertFalse("empty must be rejected", JsInstanceName.isValid(""))
        assertTrue(JsInstanceName.isValid(valid))
    }

    private fun String.escapeDump(): String =
        map { if (it.isLetterOrDigit()) it.toString() else "\\u${("%04x".format(it.code))}" }.joinToString("")
}
