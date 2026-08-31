package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretAliasAndProtocolTest {
    @Test
    fun validAliasesParse() {
        listOf("alias-1", "a.b_c-9", "A", "9tail", "a".repeat(SecretAlias.MAX_LENGTH)).forEach {
            assertEquals(it, SecretAlias(it).value)
            assertEquals(it, SecretAlias(it).toString())
        }
    }

    @Test
    fun aliasesRejectUnsafeNames() {
        val bad =
            listOf(
                "",
                "x".repeat(SecretAlias.MAX_LENGTH + 1),
                "../evil", // path traversal
                "..",
                ".hidden", // hidden file / non-alphanumeric start
                "-x",
                "_x",
                "x/y", // path separator
                "x y", // space
                "x\u0001y", // control character
            )
        bad.forEach { raw ->
            assertThrows<IllegalArgumentException>("alias accepted but must be rejected: ${raw.escape()}") {
                SecretAlias(raw)
            }
        }
        // Consecutive dots are legal characters and stay inside one directory level — accepted.
        assertEquals("x..y", SecretAlias("x..y").value)
    }

    @Test
    fun protocolParseAcceptsTheClosedSetOnly() {
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, ProviderProtocol.parse("OPENAI_RESPONSES"))
        assertEquals(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderProtocol.parse("OPENAI_CHAT_COMPLETIONS"))
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, ProviderProtocol.parse("ANTHROPIC_MESSAGES"))
        listOf("", "OPENAI", "openai_responses", "OPENAI_RESPONSES ", "GEMINI").forEach {
            assertThrows<IllegalArgumentException>("protocol parsed but must be rejected: ${it.escape()}") {
                ProviderProtocol.parse(it)
            }
        }
    }

    private fun String.escape(): String =
        map { if (it.isLetterOrDigit()) it else it.code.toString(16) }.joinToString(" ")
}
