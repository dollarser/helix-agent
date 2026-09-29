package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalUiLaunchDeviceTest {
    @Test fun sharingReportsMissingOrDeniedChooserAndPreservesPayloadOnRetry() {
        var failure: RuntimeException? = ActivityNotFoundException()
        var launched: Intent? = null
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun startActivity(intent: Intent) {
                    failure?.let { throw it }
                    launched = intent
                }
            }
        assertFalse(sharePlainText(context, "retained text"))
        failure = SecurityException()
        assertFalse(sharePlainText(context, "retained text"))
        failure = null
        assertTrue(sharePlainText(context, "retained text"))
        assertEquals(Intent.ACTION_CHOOSER, launched?.action)
        val payload = requireNotNull(launched).getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("retained text", payload?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun exportPickerFailureIsVisibleAndRetryResetsTheError() {
        var denied = true
        val registry =
            object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                    input: I,
                    options: androidx.core.app.ActivityOptionsCompat?,
                ) {
                    if (denied) throw SecurityException()
                }
            }
        val contract =
            androidx.activity.result.contract.ActivityResultContracts
                .CreateDocument("text/plain")
        val picker = registry.register("export", contract) {}
        val state = androidx.compose.runtime.mutableStateOf<ArtifactExportState>(ArtifactExportState.Idle)
        val context = ApplicationProvider.getApplicationContext<Context>()
        launchArtifactExportPicker(context, picker, "report.txt", state)
        assertTrue(state.value is ArtifactExportState.Failed)
        denied = false
        launchArtifactExportPicker(context, picker, "report.txt", state)
        assertEquals(ArtifactExportState.Idle, state.value)
        picker.unregister()
    }
}
