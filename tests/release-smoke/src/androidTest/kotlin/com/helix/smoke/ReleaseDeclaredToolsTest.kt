package com.helix.smoke

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseDeclaredToolsTest {
    @Test fun discoverySelectsCurrentNamesAndDoesNotRetainEarlierAliases() {
        assertEquals("search_a", ReleaseDeclaredTools.searchName(request("search_a", "target_a")))
        assertNull(ReleaseDeclaredTools.targetName(request("search_a", "target_a"), "ui_launch"))
        assertEquals("target_a", ReleaseDeclaredTools.targetName(discovered("target_a"), "ui_launch"))
        assertEquals("target_b", ReleaseDeclaredTools.targetName(discovered("target_b"), "ui_launch"))
    }

    @Test fun unknownHistoricalOnlyAmbiguousAndIllegalDeclarationsFailClosed() {
        assertNull(ReleaseDeclaredTools.targetName(discovered("target"), "ui.unknown"))
        val missing = discovered("target").put("tools", JSONArray())
        assertThrows(IllegalStateException::class.java) { ReleaseDeclaredTools.targetName(missing, "ui_launch") }
        val ambiguous = discovered("target")
        ambiguous.getJSONArray("tools").put(tool("second", DESCRIPTION))
        assertThrows(IllegalStateException::class.java) { ReleaseDeclaredTools.targetName(ambiguous, "ui_launch") }
        assertThrows(IllegalStateException::class.java) {
            ReleaseDeclaredTools.targetName(discovered("ui_launch"), "ui_launch")
        }
    }

    private fun request(
        search: String,
        target: String,
    ) = JSONObject()
        .put("tools", JSONArray().put(tool(search, "Search user-enabled tools by name")).put(tool(target, DESCRIPTION)))

    private fun discovered(target: String): JSONObject {
        val result =
            JSONObject()
                .put("schemaAvailability", "next_request")
                .put(
                    "tools",
                    JSONArray().put(JSONObject().put("name", "ui_launch").put("description", DESCRIPTION.take(240))),
                )
        val message = JSONObject().put("role", "tool").put("content", "[SUCCEEDED] $result\nDelivery facts: fixture")
        return request("search", target).put("messages", JSONArray().put(message))
    }

    private fun tool(
        name: String,
        description: String,
    ) = JSONObject().put("function", JSONObject().put("name", name).put("description", description))

    private companion object {
        val DESCRIPTION = "Launch fixture package. " + "Long tool description. ".repeat(20)
    }
}
