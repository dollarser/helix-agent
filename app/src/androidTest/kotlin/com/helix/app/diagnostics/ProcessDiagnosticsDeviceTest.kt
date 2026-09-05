package com.helix.app.diagnostics

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** HXA-104: real SharedPreferences and ApplicationExitInfo compatibility gates. */
@RunWith(AndroidJUnit4::class)
class ProcessDiagnosticsDeviceTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun previewIsBoundedAndNeverContainsExceptionMessageOrUserContent() {
        val marker = "hxa104-secret-prompt-body"
        val store = ProcessEvidenceStore(application) { 123_456L }
        store.heartbeat(ProcessEvidenceStore.STATE_ACTIVE)
        store.checkpointTurn("turn-104", "turn-104", "RUNNING_TOOL")
        store.recordCrash(IllegalStateException(marker))

        val encoded = DiagnosticBundlePreview.collect(store).encode()
        assertTrue(encoded.toByteArray().size <= DiagnosticBundlePreview.MAX_ENCODED_BYTES)
        assertTrue(encoded.contains("java.lang.IllegalStateException"))
        assertFalse(encoded.contains(marker))
        assertFalse(encoded.contains("message"))
        assertTrue(encoded.contains("turn-104"))
        assertTrue(encoded.contains("RUNNING_TOOL"))
        assertEquals(64, store.read().crashFingerprint?.length)
    }

    @Test
    fun checkpointRejectsContentAndUnknownState() {
        val store = ProcessEvidenceStore(application)
        assertThrows(IllegalArgumentException::class.java) {
            store.checkpointTurn("prompt body with spaces", "turn-104", "RUNNING_TOOL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.checkpointTurn("turn-104", "turn-104", "MADE_UP")
        }
    }

    @Test
    fun applicationExitInfoIsApiGatedAndBounded() {
        val exits = ProcessEvidenceStore(application).recentExits()
        assertTrue(exits.size <= ProcessEvidenceStore.MAX_EXIT_RECORDS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertTrue(exits.isEmpty())
        } else {
            assertTrue(exits.all { it.timestampMillis >= 0L })
        }
    }

    @Test
    fun uncaughtHandlerRecordsThenDelegatesToThePlatformChain() {
        var delegated: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delegated = throwable }
        val diagnostics = ProcessDiagnostics.install(application)
        val failure = IllegalArgumentException("body-that-must-not-be-recorded")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)

        assertTrue(delegated === failure)
        val evidence = ProcessEvidenceStore(application).read()
        assertEquals("java.lang.IllegalArgumentException", evidence.crashType)
        assertFalse(
            DiagnosticBundlePreview.collect(ProcessEvidenceStore(application)).encode().contains(failure.message!!),
        )
        @Suppress("UNUSED_VARIABLE")
        val keepAlive = diagnostics
    }
}
