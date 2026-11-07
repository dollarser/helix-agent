package com.helix.ui.shared

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

/** Synthetic inputs only. No real activity, clipboard, app state or external resource is changed. */
class SharedPlatformBoundaryDeviceTest {
    @Before
    fun requireIsolatedApplication() {
        requireSharedTestApplication(ApplicationProvider.getApplicationContext())
    }

    @Test fun launchingRunsExactlyOnceAndExpectedPlatformDenialsRemainFailures() {
        var calls = 0
        assertTrue(launchExternalUi { calls++ })
        assertEquals(1, calls)
        assertFalse(launchExternalUi { throw ActivityNotFoundException("Synthetic unavailable activity") })
        assertFalse(launchExternalUi { throw SecurityException("Synthetic denied activity") })
    }

    @Test fun cancellationAndUnexpectedFailuresAreNotReportedAsSuccessfulOrUnavailable() {
        val cancel = CancellationException("Synthetic cancellation")
        assertSame(cancel, assertThrows(CancellationException::class.java) { launchExternalUi { throw cancel } })
        val failure = IllegalStateException("Synthetic invariant failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { launchExternalUi { throw failure } })
    }

    @Test fun previewDecodeRejectsMalformedDataAndBoundsSyntheticImageAllocation() {
        assertNull(decodePreviewBitmap(byteArrayOf(), 128))
        assertNull(decodePreviewBitmap(byteArrayOf(1, 2, 3), 128))
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val bytes =
            ByteArrayOutputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        source.recycle()
        val decoded = requireNotNull(decodePreviewBitmap(bytes, 128))
        try {
            assertEquals(128, decoded.width)
            assertEquals(64, decoded.height)
        } finally {
            decoded.asAndroidBitmap().recycle()
        }
    }
}
