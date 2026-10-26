package com.helix.app.ui

import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.app.allfiles.AllFilesModule
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.profile.PersistedSafetyProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Renders the actual Advanced screen; never presses a grant button or changes OS permissions. */
class AllFilesPromptDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun concisePermissionCopyKeepsTheBoundaryAndDoesNotRequestPermission() {
        assumeTrue(AllFilesModule.AVAILABLE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = Environment.isExternalStorageManager()
        AllFilesModule.init(context)
        val profile = PersistedSafetyProfileStore(InMemoryLineStore(), advancedAvailable = true)
        compose.setContent { HelixTheme { AllFilesModule.render(profile) } }
        val description = context.resources.getIdentifier("allfiles_description", "string", context.packageName)
        compose
            .onNodeWithTag("allfiles-explanation")
            .assertIsDisplayed()
            .assertTextEquals(context.getString(description))
        compose.onNodeWithTag("allfiles-advanced-required").performScrollTo().assertIsDisplayed()
        assertEquals(before, Environment.isExternalStorageManager())
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            context.cacheDir.resolve("advanced-permission-copy.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
