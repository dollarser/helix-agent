package com.helix.app.infrastructure.conversation

import com.helix.core.storage.input.ComposerInputCache
import com.helix.feature.conversation.input.ChatSubmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoredConversationInputCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun cache() = StoredConversationInputCache(ComposerInputCache(temporary.newFolder()))

    @Test fun recoveryReplacesOnlyCapturedRevision() {
        val cache = cache()
        val old = ChatSubmission("s", 1, "old", "saved", revisedMessageId = "m")
        assertTrue(cache.save(old))
        val recovered = old.copy(revision = 2, clientRequestId = "recovered", revisedMessageId = null)
        assertEquals(recovered, cache.replace(old, recovered))
        assertEquals(recovered, cache.read("s"))
    }

    @Test fun newerUserEditWinsOverLateRecovery() {
        val cache = cache()
        val old = ChatSubmission("s", 1, "old", "saved", revisedMessageId = "m")
        cache.save(old)
        val edited = old.copy(revision = 2, clientRequestId = "edited", text = "new input")
        cache.save(edited)
        assertEquals(
            edited,
            cache.replace(old, old.copy(revision = 2, clientRequestId = "recovered", revisedMessageId = null)),
        )
    }

    @Test fun acknowledgedInputCannotBeResurrectedByRecovery() {
        val cache = cache()
        val old = ChatSubmission("s", 1, "old", "saved", revisedMessageId = "m")
        cache.save(old)
        cache.clear("s", 1, "old")
        assertNull(cache.replace(old, old.copy(revision = 2, clientRequestId = "recovered", revisedMessageId = null)))
        assertNull(cache.read("s"))
    }

    @Test fun changedIdentityAtSameRevisionCannotBeOverwritten() {
        val cache = cache()
        val old = ChatSubmission("s", 1, "old", "saved", revisedMessageId = "m")
        cache.save(old)
        val changed = old.copy(clientRequestId = "other", text = "different")
        cache.save(changed)
        assertEquals(
            changed,
            cache.replace(old, old.copy(revision = 2, clientRequestId = "recovered", revisedMessageId = null)),
        )
    }
}
