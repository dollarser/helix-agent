package com.helix.app.approval

import com.helix.app.R
import com.helix.app.ui.savePreferenceNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PreferenceSaveNoticeTest {
    @Test fun unavailableAndFailedWritesNeverReportSuccess() =
        runBlocking {
            assertEquals(R.string.preference_save_unavailable, savePreferenceNotice { null })
            assertEquals(
                R.string.preference_save_failed,
                savePreferenceNotice { throw IllegalStateException("fixture") },
            )
            assertEquals(R.string.preference_save_changed, savePreferenceNotice { throw PreferenceContractChanged() })
        }

    @Test fun cancellationIsNotSwallowedAsAWriteFailure() =
        runBlocking {
            try {
                savePreferenceNotice { throw CancellationException("fixture") }
                fail("must preserve cancellation")
            } catch (_: CancellationException) {
                // Expected: the UI lifecycle owns cancellation.
            }
        }
}
