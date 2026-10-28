package com.helix.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginSelectionChangesTest {
    private val ready = PluginSessionRow("a", "A", false, false, true, true, true, 1, 1)

    @Test fun `only picker-owned changes are applied and unrelated selections survive`() {
        assertEquals(
            mapOf("a" to true, "old" to false),
            pluginSelectionChanges(
                mapOf("a" to false, "old" to true),
                setOf("a"),
                setOf("old", "unrelated"),
                listOf(ready),
            ),
        )
    }

    @Test fun `stale baseline and unoffered plugin fail closed`() {
        assertThrows(IllegalStateException::class.java) {
            pluginSelectionChanges(mapOf("a" to false), setOf("a"), setOf("a"), listOf(ready))
        }
        assertThrows(IllegalArgumentException::class.java) {
            pluginSelectionChanges(mapOf("a" to false), setOf("unoffered"), emptySet(), listOf(ready))
        }
    }

    @Test fun `missing readiness fails before any change set can be returned`() {
        val unavailable =
            listOf(
                ready.copy(enabled = false),
                ready.copy(available = false),
                ready.copy(readyComponents = 0),
            )
        unavailable.forEach { row ->
            assertThrows(PluginSelectionUnavailable::class.java) {
                pluginSelectionChanges(mapOf("a" to false, "old" to true), setOf("a"), setOf("old"), listOf(row))
            }
        }
    }

    @Test fun `unavailable selected plugin can be removed and partial ready capability stays usable`() {
        assertEquals(
            mapOf("a" to false),
            pluginSelectionChanges(
                mapOf("a" to true),
                emptySet(),
                setOf("a"),
                emptyList(),
            ),
        )
        assertEquals(
            mapOf("a" to true),
            pluginSelectionChanges(
                mapOf("a" to false),
                setOf("a"),
                emptySet(),
                listOf(ready.copy(ready = false, totalComponents = 2)),
            ),
        )
    }
}
