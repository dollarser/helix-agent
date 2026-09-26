package com.helix.app.chat

import com.helix.app.internal.InMemoryLineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationLaunchStoreTest {
    @Test
    fun `missing or malformed state fails closed to new draft`() {
        val backing = InMemoryLineStore()
        val store = ConversationLaunchStore(backing)

        assertEquals(ConversationLaunchTarget.NewDraft, store.target())

        backing.setLines("conversation_launch_target", listOf("session"))
        assertEquals(ConversationLaunchTarget.NewDraft, store.target())

        backing.setLines("conversation_launch_target", listOf("other", "session-1"))
        assertEquals(ConversationLaunchTarget.NewDraft, store.target())
    }

    @Test
    fun `new draft and durable session selections round trip`() {
        val backing = InMemoryLineStore()
        val store = ConversationLaunchStore(backing)

        store.selectSession("session-1")
        assertEquals(ConversationLaunchTarget.Session("session-1"), store.target())

        store.selectNewDraft()
        assertEquals(ConversationLaunchTarget.NewDraft, store.target())
    }

    @Test
    fun `session ids cannot corrupt the line encoding`() {
        val store = ConversationLaunchStore(InMemoryLineStore())

        assertThrows(IllegalArgumentException::class.java) { store.selectSession("") }
        assertThrows(IllegalArgumentException::class.java) { store.selectSession("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { store.selectSession("a\u0000b") }
    }
}
