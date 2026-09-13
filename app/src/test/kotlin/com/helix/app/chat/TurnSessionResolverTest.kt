package com.helix.app.chat

import com.helix.core.storage.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TurnSessionResolver] (research doc section 34; HX2-01): the session a turn start
 * runs in. An explicitly addressed session must run in exactly that session; an explicit session
 * that cannot be resolved is REFUSED — never substituted with the open session (which would run the
 * request in the wrong session). Only a start with no explicit session falls back to the open one.
 */
class TurnSessionResolverTest {
    private val open = SessionEntity("open", "Open", null, null, 0L, null)
    private val explicit = SessionEntity("explicit", "Explicit", null, null, 0L, null)

    private fun resolver(vararg resolvable: String): (String) -> SessionEntity? =
        { id -> if (id in resolvable) SessionEntity(id, "S:$id", null, null, 0L, null) else null }

    @Test
    fun anExplicitSessionRunsInExactlyThatSession() {
        assertEquals(explicit.id, TurnSessionResolver.resolve("explicit", open, resolver("explicit"))?.id)
    }

    @Test
    fun anExplicitSessionThatCannotResolveIsRefusedNotFallBackToOpen() {
        // The bug this exists to prevent: the caller addressed "explicit"; it must NOT run in "open".
        assertNull(TurnSessionResolver.resolve("explicit", open, resolver()))
    }

    @Test
    fun anExplicitSessionIsRefusedEvenWhenAOpenSessionIsLive() {
        assertNull(TurnSessionResolver.resolve("explicit", open, resolver("open")))
    }

    @Test
    fun aStartWithNoExplicitSessionFallsBackToTheOpenSession() {
        assertEquals(open.id, TurnSessionResolver.resolve(null, open, resolver("open"))?.id)
    }

    @Test
    fun aStartWithNoExplicitSessionAndNoOpenSessionIsRefused() {
        assertNull(TurnSessionResolver.resolve(null, null, resolver()))
    }
}
