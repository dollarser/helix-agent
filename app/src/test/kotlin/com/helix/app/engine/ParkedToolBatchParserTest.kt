package com.helix.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParkedToolBatchParserTest {
    @Test
    fun `preserves wire and local identities in provider call order`() {
        val calls =
            ParkedToolBatchParser.parse(
                """[{"id":"wire-b","localId":"local-2","name":"write","arguments":"{}"},""" +
                    """{"id":"wire-a","localId":"local-1","name":"read","arguments":"{\"path\":\"a\"}"}]""",
            )

        assertEquals(listOf("wire-b", "wire-a"), calls.map { it.wireId })
        assertEquals(listOf("local-2", "local-1"), calls.map { it.localId })
    }

    @Test
    fun `rejects legacy or corrupt rows that cannot bridge local identity`() {
        assertThrows(IllegalStateException::class.java) {
            ParkedToolBatchParser.parse("""[{"id":"wire","name":"write","arguments":"{}"}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParkedToolBatchParser.parse(
                """[{"id":"wire","localId":"local","name":"write","arguments":"{}"},""" +
                    """{"id":"wire","localId":"other","name":"read","arguments":"{}"}]""",
            )
        }
    }
}
