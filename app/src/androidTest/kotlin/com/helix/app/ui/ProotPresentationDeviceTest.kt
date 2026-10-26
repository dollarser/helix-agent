package com.helix.app.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.HelixTheme
import com.helix.app.application.runtime.ProotInstallationStatus
import com.helix.app.proot.ProotToolModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic UI callbacks only; no installation, process, permission or real runtime maintenance. */
class ProotPresentationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passiveStatusDoesNotOpenMaintenance() {
        val queried = AtomicInteger()
        val opened = AtomicInteger()
        val expected = AtomicReference<String>()
        compose.setContent {
            expected.set(stringResource(ProotInstallationStatus.NOT_PREPARED.labelRes()))
            HelixTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                        ProotRuntimeSection(
                            queryStatus = {
                                queried.incrementAndGet()
                                ProotInstallationStatus.NOT_PREPARED
                            },
                            openManager = {
                                opened.incrementAndGet()
                                true
                            },
                        )
                    }
                }
            }
        }
        compose.waitUntil(5000) { queried.get() > 0 }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-proot-status").assertTextEquals(expected.get())
        compose.onNodeWithTag("settings-proot-manage").assertIsDisplayed()
        assertEquals(0, opened.get())
        screenshot("passive")
    }

    @Test fun failedOpenIsVisibleAndExplicitRetryClearsIt() {
        val opened = AtomicInteger()
        compose.setContent {
            HelixTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().padding(24.dp)) {
                        ProotRuntimeSection(
                            queryStatus = { ProotInstallationStatus.RESET_REQUIRED },
                            openManager = { opened.incrementAndGet() > 1 },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("settings-proot-manage").performClick()
        compose.onNodeWithTag("settings-proot-entry-error").assertIsDisplayed()
        assertEquals(1, opened.get())
        screenshot("entry-error")
        compose.onNodeWithTag("settings-proot-manage").performClick()
        compose.onNodeWithTag("settings-proot-entry-error").assertDoesNotExist()
        assertEquals(2, opened.get())
    }

    @Test fun passiveAdapterDoesNotStartRuntimeProcess(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            require(context.packageName.endsWith(".asrvalidation"))
            (context.applicationContext as HelixApplication).awaitAppContainer()
            val manager = context.getSystemService(ActivityManager::class.java)

            fun runtimeProcesses() =
                manager.runningAppProcesses
                    .orEmpty()
                    .filter { it.processName == "${context.packageName}:proot" }
                    .map { it.pid }
                    .toSet()
            val before = runtimeProcesses()
            assertEquals(emptySet<Int>(), before)
            val expected =
                if (ProotToolModule.AVAILABLE) {
                    ProotInstallationStatus.NOT_PREPARED
                } else {
                    ProotInstallationStatus.NOT_AVAILABLE
                }
            repeat(3) { assertEquals(expected, ProotToolModule.installationStatus()) }
            assertEquals(before, runtimeProcesses())
        }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".asrvalidation"))
        val directory = File(context.cacheDir, "architecture-closeout").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
    }
}
