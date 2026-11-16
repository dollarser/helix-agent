package com.helix.app.test

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeclaredFixtureToolsTest {
    @Test fun currentDeclarationWinsOverPreviousAliasAndCanonicalName() {
        assertEquals("opaque_a", select(request("opaque_a")))
        assertEquals("different_b", select(request("different_b")))
    }

    @Test fun responsesFlatDeclarationUsesTheSameRequestBoundSelector() {
        val request = JSONObject().put("tools", JSONArray().put(function("flat_name")))
        assertEquals("flat_name", select(request))
    }

    @Test fun missingAmbiguousAndInvalidNamesFailInsteadOfGuessing() {
        assertThrows(IllegalStateException::class.java) { select(JSONObject().put("tools", JSONArray())) }
        val ambiguous = request("first")
        ambiguous.getJSONArray("tools").put(JSONObject().put("function", function("second")))
        assertThrows(IllegalStateException::class.java) { select(ambiguous) }
        assertThrows(IllegalStateException::class.java) { select(request("time_now")) }
        assertThrows(IllegalStateException::class.java) {
            declaredFixtureToolName(request("first").toString(), "unknown", "Unregistered tool description")
        }
    }

    private fun select(request: JSONObject) = declaredFixtureToolName(request.toString(), "time_now", "Fixture clock")

    private fun request(name: String) =
        JSONObject().put("tools", JSONArray().put(JSONObject().put("function", function(name))))

    private fun function(name: String) =
        JSONObject().put("name", name).put("description", "Fixture clock. Added runtime instructions.")
}
