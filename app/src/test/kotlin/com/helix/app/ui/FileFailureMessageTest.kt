package com.helix.app.ui

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class FileFailureMessageTest {
    @Test fun unavailableFileAndDeniedAccessKeepTheirDistinctActions() {
        assertEquals(
            R.string.files_file_unavailable,
            fileFailureMessage(FileNotFoundException(), R.string.files_share_failed),
        )
        assertEquals(
            R.string.files_access_failed,
            fileFailureMessage(SecurityException(), R.string.files_preview_failed),
        )
    }

    @Test fun nativeAccessDenialDoesNotExposeItsPath() {
        assertEquals(
            R.string.files_access_failed,
            fileFailureMessage(java.nio.file.AccessDeniedException("/private/synthetic"), R.string.files_share_failed),
        )
    }

    @Test fun unknownFailureUsesOperationSpecificCopyInsteadOfExceptionDetails() {
        val failure = IOException("synthetic private path /private/example and vendor detail")
        assertEquals(R.string.files_share_failed, fileFailureMessage(failure, R.string.files_share_failed))
        assertEquals(R.string.files_preview_failed, fileFailureMessage(failure, R.string.files_preview_failed))
    }
}
