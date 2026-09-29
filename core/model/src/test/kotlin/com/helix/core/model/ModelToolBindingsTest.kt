package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelToolBindingsTest {
    private val ref =
        ToolBindingRef(ToolName("internal.read"), ToolVersion(1), "a".repeat(64), "native", "v1", "process-1")
    private val schema = ModelToolSchema(ToolName("read"), "Read", """{"type":"object"}""", bindingRef = ref)

    @Test fun aliasesResolveOnlyThroughTheActualExposureSnapshot() {
        val source = mutableListOf(schema)
        val snapshot = ModelToolBindings(source)
        source.clear()
        assertEquals(ref, snapshot.resolve("read"))
        assertNull(snapshot.resolve("internal.read"))
        assertNull(snapshot.resolve("write"))
        assertNull(ModelToolBindings(emptyList()).resolve("read"))
        assertNull(ModelToolBindings(listOf(schema.copy(bindingRef = null))).resolve("read"))
        val changed = ModelToolBindings(listOf(schema.copy(bindingRef = ref.copy(incarnation = "process-2"))))
        assertEquals("process-1", snapshot.resolve("read")?.incarnation)
        assertEquals("process-2", changed.resolve("read")?.incarnation)
    }

    @Test fun ambiguousAliasCannotSilentlyOverwriteAnEntry() {
        assertThrows(IllegalArgumentException::class.java) { ModelToolBindings(listOf(schema, schema)) }
    }

    @Test fun compactManifestPreservesExactBindingAndEscapesControlCharacters() {
        val entry = ExposedToolBinding("read", ref.copy(owner = "owner\u0001\n\""))
        val manifest = CompactManifestCodec.bounded("call", 1, null, emptyList(), emptyList(), tools = listOf(entry))
        assertEquals(listOf(entry), manifest.tools)
        val encoded = CompactManifestCodec.encodeCompact(manifest)
        assertTrue(encoded.contains("\"tools\":[[\"read\",\"internal.read\",\"1\""))
        assertTrue(encoded.contains("owner\\u0001\\n\\\""))
        assertTrue(encoded.contains("\"v1\",\"process-1\""))
        assertThrows(IllegalArgumentException::class.java) {
            CompactManifestCodec.bounded("call", 1, null, emptyList(), emptyList(), tools = listOf(entry, entry))
        }
    }
}
