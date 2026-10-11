package com.helix.extensions.chatreply.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class NewIncomingMessageGateTest {
    private val gate = NewIncomingMessageGate()
    private val me = ChatMessage(MessageSide.ME, "old reply")
    private val other = ChatMessage(MessageSide.OTHER, "old question")

    @Test
    fun ignoresHistoryScrollAndRecognizesNewIncomingAtBottom() {
        assertEquals(NewIncomingMessageGate.Result.FIRST_OBSERVATION, gate.observe(listOf(other, me)))
        assertEquals(NewIncomingMessageGate.Result.NO_NEW_MESSAGE, gate.observe(listOf(other)))
        assertEquals(
            NewIncomingMessageGate.Result.NEW_INCOMING,
            gate.observe(listOf(other, me, ChatMessage(MessageSide.OTHER, "new question"))),
        )
    }

    @Test
    fun distinguishesNewOutgoingFromIncoming() {
        gate.observe(listOf(other))
        assertEquals(
            NewIncomingMessageGate.Result.NEW_OUTGOING,
            gate.observe(listOf(other, me)),
        )
    }
}
