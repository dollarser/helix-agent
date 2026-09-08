package com.helix.app.diagnostics

import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host-controlled phases deliberately terminate the target process; never run implicitly. */
class ProcessDeathEvidenceDeviceTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun verifyPreviousProcessEvidence() {
        val phase = arguments.getString("helix.diagnostics.phase")
        assumeTrue(phase == "verify-crash" || phase == "verify-anr")
        val store = ProcessEvidenceStore(application)
        val encoded = DiagnosticBundlePreview.collect(store).encode()
        File(application.getExternalFilesDir(null), "hxa104-preview.json").writeText(encoded)
        assertFalse(encoded.contains("hxa104-private-marker-never-in-preview"))
        if (phase == "verify-crash") {
            assertEquals("java.lang.IllegalStateException", store.read().crashType)
            assertEquals(64, store.read().crashFingerprint?.length)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val expected =
                if (phase ==
                    "verify-crash"
                ) {
                    ApplicationExitInfo.REASON_CRASH
                } else {
                    ApplicationExitInfo.REASON_ANR
                }
            val since = requireNotNull(arguments.getString("helix.diagnostics.since")).toLong()
            assertTrue(
                "missing new platform exit reason $expected",
                store.recentExits().any {
                    it.reason == expected &&
                        it.timestampMillis >= since
                },
            )
        }
    }
}
