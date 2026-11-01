package com.helix.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDraftSessionTest {
    @Test fun cumulativeSegmentsDoNotDuplicateAndKnownBufferAcksDoNotRevoke() {
        val draft = VoiceDraftSession("Original")
        assertEquals("Original first", draft.commit("first", "Original"))
        assertEquals("Original first second", draft.commit("first second", "Original"))
        assertTrue(draft.accepts("Original first"))
        assertEquals("Original first second", draft.commit("first second", "Original first second"))
        assertEquals("Original first second partial", draft.preview("first second partial"))
        assertNull(draft.commit("late", "My manual edit"))
        draft.cancel()
        assertNull(draft.commit("late", "Original first second"))
    }

    @Test fun retryUsesTheAlreadyCommittedDraftAsItsNewOrigin() {
        val retry = VoiceDraftSession("Original first")
        assertEquals("Original first next", retry.commit("next", "Original first"))
        assertEquals("Original first", retry.preview(""))
        assertEquals("Original first", VoiceInputMapper.appendDraft("Original first", "   "))
    }
}
