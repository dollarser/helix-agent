package com.helix.tools.framework

import com.helix.core.model.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaHashTest {
    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun keyOrderDoesNotChangeTheHash() {
        val a = json("""{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]}""")
        val b = json("""{"required":["x"],"properties":{"x":{"type":"string"}},"type":"object"}""")
        val c = json("""{"properties":{"x":{"type":"string"}},"type":"object","required":["x"]}""")
        assertEquals(ToolDescriptor.schemaHashOf(a, json("{}")), ToolDescriptor.schemaHashOf(b, json("{}")))
        assertEquals(ToolDescriptor.schemaHashOf(b, json("{}")), ToolDescriptor.schemaHashOf(c, json("{}")))
    }

    @Test
    fun nestedObjectKeysAreSortedAtEveryLevel() {
        val a = json("""{"outer":{"z":1,"a":{"y":true,"b":"s"}}}""")
        val b = json("""{"outer":{"a":{"b":"s","y":true},"z":1}}""")
        assertEquals(ToolDescriptor.schemaHashOf(a, json("{}")), ToolDescriptor.schemaHashOf(b, json("{}")))
    }

    @Test
    fun arrayOrderIsSignificant() {
        val a = json("""{"required":["x","y"]}""")
        val b = json("""{"required":["y","x"]}""")
        assertNotEquals(ToolDescriptor.schemaHashOf(a, json("{}")), ToolDescriptor.schemaHashOf(b, json("{}")))
    }

    @Test
    fun semanticDifferencesChangeTheHash() {
        val base = json("""{"type":"string"}""")
        assertNotEquals(
            ToolDescriptor.schemaHashOf(base, json("{}")),
            ToolDescriptor.schemaHashOf(json("""{"type":"number"}"""), json("{}")),
        )
        assertNotEquals(
            ToolDescriptor.schemaHashOf(base, json("{}")),
            ToolDescriptor.schemaHashOf(base, json("""{"type":"string"}""")),
        )
        // input and output are distinct roles: swapping them changes the hash
        val input = json("""{"type":"object"}""")
        val output = json("""{"type":"array"}""")
        assertNotEquals(ToolDescriptor.schemaHashOf(input, output), ToolDescriptor.schemaHashOf(output, input))
    }

    @Test
    fun stringContentIncludingEscapesIsCanonical() {
        val a = json("""{"description":"line1\nline2 \"quoted\""}""")
        val b = json("""{"description":"line1\nline2 \"quoted\""}""")
        assertEquals(ToolDescriptor.schemaHashOf(a, json("{}")), ToolDescriptor.schemaHashOf(b, json("{}")))
        // a control character inside a schema string is escaped in the
        // canonical form, so it cannot collide with the hash separator
        val withControl = json("""{"description":"bad\u0001char"}""")
        val withoutControl = json("""{"description":"badchar"}""")
        assertNotEquals(
            ToolDescriptor.schemaHashOf(withControl, json("{}")),
            ToolDescriptor.schemaHashOf(withoutControl, json("{}")),
        )
        // deterministic across calls
        assertEquals(
            ToolDescriptor.schemaHashOf(withControl, json("{}")),
            ToolDescriptor.schemaHashOf(withControl, json("{}")),
        )
    }

    @Test
    fun numbersAreHashedByTheirParsedLiteral() {
        val a = json("""{"minItems":3}""")
        val b = json("""{"minItems":3}""")
        val c = json("""{"minItems":4}""")
        assertEquals(ToolDescriptor.schemaHashOf(a, json("{}")), ToolDescriptor.schemaHashOf(b, json("{}")))
        assertNotEquals(ToolDescriptor.schemaHashOf(a, json("{}")), ToolDescriptor.schemaHashOf(c, json("{}")))
    }

    @Test
    fun hashIsCanonicalLowercaseHex() {
        val hash: Sha256 = ToolDescriptor.schemaHashOf(json("""{"a":1}"""), json("{}"))
        assertEquals(64, hash.hex.length)
        hash.hex.forEach { c ->
            assertTrue("hash must be lowercase hex: $c", c in '0'..'9' || c in 'a'..'f')
        }
    }
}
