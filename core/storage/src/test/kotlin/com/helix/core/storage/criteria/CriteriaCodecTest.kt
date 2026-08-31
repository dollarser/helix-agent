package com.helix.core.storage.criteria

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ToolCallId
import com.helix.core.storage.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CriteriaCodecTest {
    private val evidence =
        StoredEvidence(
            verifier = "verifier.login",
            artifactRef = ArtifactRef("artifact-1"),
            toolCallId = ToolCallId("tc-42"),
        )

    @Test
    fun `encodes an empty list`() {
        assertEquals("[]", CriteriaCodec.encode(emptyList()))
        assertEquals(emptyList<String>(), CriteriaCodec.decode("[]"))
    }

    @Test
    fun `known vector for unsatisfied and satisfied criteria`() {
        val criteria =
            listOf(
                StoredCriterion("c1", "Login works", null),
                StoredCriterion("c2", "Data syncs", evidence),
            )
        val encoded = CriteriaCodec.encode(criteria)
        val expected =
            """[{"id":"c1","description":"Login works","satisfied":false,"evidence":null},""" +
                """{"id":"c2","description":"Data syncs","satisfied":true,"evidence":{"verifier":"verifier.login",""" +
                """"artifactRef":"artifact-1","toolCallId":"tc-42"}}]"""
        assertEquals(expected, encoded)
        assertEquals(criteria, CriteriaCodec.decode(encoded))
    }

    @Test
    fun `null references are encoded as JSON null`() {
        val onlyArtifact = StoredEvidence("v", ArtifactRef("a-1"), null)
        val encoded = CriteriaCodec.encode(listOf(StoredCriterion("c", "d", onlyArtifact)))
        assertTrue(encoded.contains("\"artifactRef\":\"a-1\",\"toolCallId\":null"))
        val decoded = CriteriaCodec.decode(encoded)
        assertNull(decoded.single().evidence?.toolCallId)
        assertEquals(
            "a-1",
            decoded
                .single()
                .evidence
                ?.artifactRef
                ?.value,
        )
    }

    @Test
    fun `escapes strings in both directions`() {
        val criterion = StoredCriterion("c1", "has \"quotes\" and \\ backslash and\nnewline", null)
        val decoded = CriteriaCodec.decode(CriteriaCodec.encode(listOf(criterion)))
        assertEquals(criterion, decoded.single())
    }

    @Test
    fun `satisfied is derived from evidence presence`() {
        assertFalse(StoredCriterion("c", "d", null).satisfied)
        assertTrue(StoredCriterion("c", "d", evidence).satisfied)
    }

    @Test
    fun `rejects unsatisfied evidence requirements`() {
        assertThrows("evidence without references") { StoredEvidence("v", null, null) }
        assertThrows("blank verifier") { StoredEvidence(" ", null, ToolCallId("t1")) }
        assertThrows("long verifier") { StoredEvidence("v".repeat(129), null, ToolCallId("t1")) }
        assertThrows("bad criterion id") { StoredCriterion("", "d", null) }
        assertThrows("bad criterion id char") { StoredCriterion("c 1", "d", null) }
        assertThrows("blank description") { StoredCriterion("c", " ", null) }
        assertThrows("long description") { StoredCriterion("c", "d".repeat(1025), null) }
    }

    @Test
    fun `rejects duplicate ids and oversized lists`() {
        val dup =
            listOf(
                StoredCriterion("c", "a", null),
                StoredCriterion("c", "b", null),
            )
        assertThrows("duplicate ids") { CriteriaCodec.encode(dup) }
        assertThrows("too many criteria") {
            CriteriaCodec.encode((1..33).map { StoredCriterion("c$it", "d", null) })
        }
    }

    @Test
    fun `rejects malformed decodes`() {
        val good = CriteriaCodec.encode(listOf(StoredCriterion("c1", "d", null)))
        assertThrows("object instead of array") { CriteriaCodec.decode("""{"id":"c1"}""") }
        assertThrows("unknown field") {
            CriteriaCodec.decode("""[{"id":"c1","description":"d","satisfied":false,"evidence":null,"x":1}]""")
        }
        assertThrows("wrong field order") {
            CriteriaCodec.decode("""[{"description":"d","id":"c1","satisfied":false,"evidence":null}]""")
        }
        assertThrows("duplicate id in decode") {
            val dup =
                """[{"id":"c1","description":"a","satisfied":false,"evidence":null},""" +
                    """{"id":"c1","description":"b","satisfied":false,"evidence":null}]"""
            CriteriaCodec.decode(dup)
        }
        assertThrows("flag disagrees with evidence") {
            CriteriaCodec.decode("""[{"id":"c1","description":"d","satisfied":true,"evidence":null}]""")
        }
        assertThrows("evidence object with missing field") {
            CriteriaCodec.decode("""[{"id":"c1","description":"d","satisfied":true,"evidence":{"verifier":"v"}}]""")
        }
        assertThrows("satisfied as number") {
            CriteriaCodec.decode("""[{"id":"c1","description":"d","satisfied":1,"evidence":null}]""")
        }
        // trailing whitespace is insignificant; trailing content is not:
        assertThrows("trailing content") { CriteriaCodec.decode("$good x") }
        assertEquals(1, CriteriaCodec.decode("$good ").size)
    }
}
