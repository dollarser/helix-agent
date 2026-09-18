package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** The required host follow-up kills a NON-instrumented main process and checks the original Runtime PID. */
class ProotDetachedOwnerDeathDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainProcessDeathKeepsTheOriginalRuntimeJob() {
        val context = ApplicationProvider.getApplicationContext<HelixApplication>()
        ensureInstalledRuntime(context)
        val token = UUID.randomUUID().toString()
        val permit = File(context.noBackupFilesDir, "detached-probe-permit")
        permit.writeText(token)
        assertEquals(token, permit.readText())
        // Instrumentation finish/crash force-stops the entire package. The host follow-up
        // starts the token-bound debug Activity afterwards and checks survival outside instrumentation.
    }
}
