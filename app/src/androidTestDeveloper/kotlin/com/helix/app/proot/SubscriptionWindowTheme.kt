package com.helix.app.proot

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import java.io.File

/** WindowManager's actual remote window flags, not a local copy of its theme resources. */
internal object SubscriptionWindowTheme {
    fun verify(
        activityName: String,
        night: Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val windows =
            instrumentation.uiAutomation.executeShellCommand("dumpsys window windows").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
        val window =
            windows
                .split(Regex("(?m)^  Window #"))
                .drop(1)
                .first { it.substringBefore('\n').contains(activityName) }
        val directory = File(instrumentation.targetContext.cacheDir, "subscription-theme").apply { mkdirs() }
        File(directory, "${activityName.substringAfterLast('.')}-$night-window.txt").writeText(window)
        assertEquals("Status bar icon mode: $activityName", !night, window.contains("LIGHT_STATUS_BAR"))
        assertEquals("Navigation bar icon mode: $activityName", !night, window.contains("LIGHT_NAVIGATION_BAR"))
    }
}
