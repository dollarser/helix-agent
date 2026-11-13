package com.helix.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDraftSaveTest {
    private fun session() = ProviderDraftSaveSession("stable-provider", null, ProviderModelSelection())

    @Test fun completedSaveIsNotReplayedAfterLostUiCallback() {
        val session = session()
        var creates = 0
        repeat(2) {
            persistProviderDraft(session, { creates++ }, { error("No rollback") }, { false })
        }
        assertEquals(1, creates)
        assertTrue(session.completed)
        assertEquals("stable-provider", session.providerId)
    }

    @Test fun failedWriteAfterSideEffectRestoresAndRetryUsesSameIdentity() {
        val session = session()
        var storedId: String? = null
        var selection = "old"
        assertThrows(IllegalArgumentException::class.java) {
            persistProviderDraft(
                session,
                {
                    storedId = session.providerId
                    selection = "new"
                    throw IllegalArgumentException("disk failure")
                },
                {
                    storedId = null
                    selection = "old"
                },
                { storedId == null && selection == "old" },
            )
        }
        assertFalse(session.completed)
        assertFalse(session.recoveryRequired)
        persistProviderDraft(session, { storedId = session.providerId }, { error("No rollback") }, { false })
        assertEquals("stable-provider", storedId)
    }

    @Test fun failedRestorationNeverReportsSuccessOrAllowsReplay() {
        val session = session()
        var attempts = 0
        repeat(2) {
            assertThrows(ProviderDraftSaveRecoveryRequiredException::class.java) {
                persistProviderDraft(
                    session,
                    {
                        attempts++
                        error("write failed")
                    },
                    { error("restore failed") },
                    { true },
                )
            }
        }
        assertEquals(1, attempts)
        assertFalse(session.completed)
        assertTrue(session.recoveryRequired)
    }

    @Test fun unverifiedRollbackAlsoRequiresRecovery() {
        val session = session()
        assertThrows(ProviderDraftSaveRecoveryRequiredException::class.java) {
            persistProviderDraft(session, { error("write failed") }, {}, { false })
        }
        assertTrue(session.recoveryRequired)
        assertFalse(session.completed)
    }
}
