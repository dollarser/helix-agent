package com.helix.app.chat

import com.helix.app.infrastructure.conversation.LineConversationLaunchStore
import com.helix.app.internal.InMemoryLineStore
import com.helix.feature.conversation.application.ConversationLaunchStore
import com.helix.feature.conversation.application.ConversationLaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationLaunchStoreTest {
    @Test fun currentConversationIncludingNoModelTakesPrecedenceOverDefault() {
        val store = LineConversationLaunchStore(InMemoryLineStore())
        store.selectDraftModel("builtin", "qwen")
        assertEquals("builtin" to "qwen", store.draftModelFor(null))
        assertEquals("custom" to "chosen", store.draftModelFor("custom" to "chosen"))
        assertEquals(null to null, store.draftModelFor(null to null))
    }

    @Test fun explicitChoiceAndNoModelSurviveRestartWithoutAutomaticSeeding() {
        val backing = InMemoryLineStore()
        LineConversationLaunchStore(backing).selectDraftModel("builtin", "qwen")
        assertEquals("builtin" to "qwen", LineConversationLaunchStore(backing).draftModel())
        LineConversationLaunchStore(backing).selectDraftModel("custom", "chosen")
        assertEquals("custom" to "chosen", LineConversationLaunchStore(backing).draftModel())
        LineConversationLaunchStore(backing).selectDraftModel(null, null)
        assertEquals(null, LineConversationLaunchStore(backing).draftModel())
    }

    @Test fun existingInstallAndMalformedPreferencesNeverFallBackToBuiltIn() {
        val backing = InMemoryLineStore()
        val store = LineConversationLaunchStore(backing)
        assertEquals(null, store.draftModel())
        backing.setLines("conversation_draft_model", listOf("invalid"))
        assertEquals(null, store.draftModel())
        assertThrows(IllegalArgumentException::class.java) { store.selectDraftModel("provider", "bad\nmodel") }
    }

    @Test
    fun `missing or malformed state fails closed to new draft`() {
        val backing = InMemoryLineStore()
        val store = LineConversationLaunchStore(backing)

        assertEquals(ConversationLaunchTarget.NewDraft, store.target())

        backing.setLines("conversation_launch_target", listOf("session"))
        assertEquals(ConversationLaunchTarget.NewDraft, store.target())

        backing.setLines("conversation_launch_target", listOf("other", "session-1"))
        assertEquals(ConversationLaunchTarget.NewDraft, store.target())
    }

    @Test
    fun `new draft and durable session selections round trip`() {
        val backing = InMemoryLineStore()
        val store = LineConversationLaunchStore(backing)

        store.selectSession("session-1")
        assertEquals(ConversationLaunchTarget.Session("session-1"), store.target())

        store.selectNewDraft()
        assertEquals(ConversationLaunchTarget.NewDraft, store.target())
    }

    @Test
    fun `session ids cannot corrupt the line encoding`() {
        val store = LineConversationLaunchStore(InMemoryLineStore())

        assertThrows(IllegalArgumentException::class.java) { store.selectSession("") }
        assertThrows(IllegalArgumentException::class.java) { store.selectSession("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { store.selectSession("a\u0000b") }
    }
}
