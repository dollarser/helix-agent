package com.helix.feature.conversation.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPayloadPresentationTest {
    @Test fun skillMetadataIsRemovedOnlyAtItsDeclaredSchemaLocations() {
        val raw =
            """
            {"entries":[{"name":"notes",
            "snapshotHash":"internal-hash",
            "ownerId":"internal-owner",
            "description":"Explain snapshotHash and callId in a document"}],
            "nextOffset":1,
            "eof":true}
            """.trimIndent()
        val shown = presentToolPayload("skills.list", raw).text
        assertTrue(shown.contains("notes"))
        assertTrue(shown.contains("Explain snapshotHash and callId in a document"))
        assertFalse(shown.contains("internal-hash"))
        assertFalse(shown.contains("internal-owner"))
        assertTrue(raw.contains("internal-hash"))
    }

    @Test fun skillInstructionsAndBusinessFieldsAreNeverFilteredAsMetadata() {
        val instructions = "The snapshotHash field and callId must be checked."
        val raw =
            """
            {
                "name":"notes", "snapshotHash":"hidden", "instructions":"$instructions",
                "error":{"callId":"customer-order"}
            }
            """.trimIndent()
        val shown = presentToolPayload("skills.read", raw).text
        assertTrue(shown.contains(instructions))
        assertTrue(shown.contains("customer-order"))
        val external = presentToolPayload("mcp.business.read", raw).text
        assertTrue(external.contains("hidden"))
        assertTrue(external.contains("customer-order"))
    }

    @Test fun automationNodeIdentityIsHiddenWithoutLosingObservedTextOrUncertainty() {
        val raw =
            """
            {"windowId":88,
            "generation":3,
            "observationStatus":"UNSETTLED",
            "truncated":true,
            "nodes":[{"token":"private-node",
            "parentToken":"parent-node",
            "text":"snapshotHash is a visible label",
            "enabled":true}]}
            """.trimIndent()
        val shown = presentToolPayload("ui.snapshot", raw).text
        assertFalse(shown.contains("private-node"))
        assertFalse(shown.contains("parent-node"))
        assertFalse(shown.contains("88"))
        assertTrue(shown.contains("snapshotHash is a visible label"))
        assertTrue(shown.contains("UNSETTLED"))
        assertTrue(shown.contains("truncated"))
    }

    @Test fun plainErrorsCodeAndMalformedBusinessTextStayIntact() {
        listOf(
            "TARGET_NOT_FOUND: No click dispatched. snapshotHash must be checked.",
            "const callId = 'business';\nconsole.log(callId)",
            "{broken JSON containing snapshotHash",
        ).forEach { assertEquals(it, presentToolPayload("skills.read", it).text) }
    }

    @Test fun truncatedJsonSummaryNeverFallsBackToRawInternalMetadata() {
        val view = presentToolPayload("skills.list", "{\"snapshotHash\":\"internal-hash\",\"entries\":[…")
        assertTrue(view.incomplete)
        assertEquals("", view.text)
        assertEquals("ordinary prose…", presentToolPayload("read", "ordinary prose…").text)
    }

    @Test fun diagnosticViewerKeepsOriginalFieldsWhileSearchUsesOnlyDisplayContent() {
        val raw = """{"name":"notes","snapshotHash":"private-hash","instructions":"business content"}"""
        assertTrue(formatToolPayload(raw).contains("private-hash"))
        val row = ToolTimelineRow("turn", "call", "skills.read", raw, "COMPLETED", raw, null)
        val screen =
            ChatScreenState(emptyList(), "session", null, emptyList(), emptyList(), null, null, null, null)
                .copy(toolTimeline = listOf(row))
        assertTrue(findMatches("private-hash", screen).isEmpty())
        val matches = findMatches("business content", screen)
        assertEquals(1, matches.size)
        assertFalse(matches.single().snippet.contains("private-hash"))
        assertEquals(raw, row.resultSummary)
        assertEquals(raw, row.requestSummary)
    }

    @Test fun multilineBusinessContentKeepsItsWhitespaceAndEmptyValues() {
        val raw = """{"content":"  code\n\n","emptyValue":""}"""
        val shown = presentToolPayload("read", raw).text
        assertTrue(shown.contains("  code\n\n"))
        assertTrue(shown.contains("emptyValue: \"\""))
    }

    @Test fun emptyArgumentsDoNotGeneratePlaceholderContent() {
        assertEquals("", presentToolPayload("read", "{}").text)
        assertEquals("", presentToolPayload("skills.read", """{"snapshotHash":"hidden","ownerId":"hidden"}""").text)
    }
}
