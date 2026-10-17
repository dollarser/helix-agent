package com.helix.app.proot

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.terminal.ManualTerminalActivity
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.app.ProotRuntimeRemoval
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeInstallation
import com.helix.runtime.proot.core.RuntimeInstallationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Destructive fixture restricted to an explicitly owned, disposable application installation. */
class BundledLinuxFirstUseDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = ProotRuntimeInstaller.runtimeRoot(context)

    @Before fun beginWithMissingEnvironment() {
        check(InstrumentationRegistry.getArguments().getString("hxa305OwnedEnvironment") == "true")
        assertTrue(ProotRuntimeRemoval.remove(root).removed)
        assertEquals(
            RuntimeInstallationState.NOT_PREPARED,
            RuntimeInstallation.inspect(root, ProotRuntimeInstaller.loadEmbeddedLock(context)).state,
        )
    }

    @Test fun firstJobPreparesAndManualTerminalReusesTheSameInstallation() {
        runActualJob()
        val original = requireNotNull(RootFsInstaller.currentActive(root)).installId
        runActualTerminal()
        assertEquals(original, RootFsInstaller.currentActive(root)?.installId)
    }

    @Test fun firstManualTerminalPreparesAndJobReusesTheSameInstallation() {
        runActualTerminal()
        val original = requireNotNull(RootFsInstaller.currentActive(root)).installId
        runActualJob()
        assertEquals(original, RootFsInstaller.currentActive(root)?.installId)
    }

    @Test fun concurrentFirstJobsPrepareExactlyOneInstallation() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit { runActualJob() } }
            results.forEach { it.get(240, TimeUnit.SECONDS) }
            assertEquals(1, root.listFiles()!!.count { it.name.startsWith("inst_") })
            assertEquals(null, RootFsInstaller.currentRollback(root))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runActualJob() {
        val fixture = ProotJobE2eDeviceTest()
        try {
            fixture.aJobSubmittedFromTheMainAppRunsUnderProotAndReturnsVerifiedOutput()
        } finally {
            fixture.cleanScratch()
        }
    }

    private fun runActualTerminal() {
        ActivityScenario.launch(ManualTerminalActivity::class.java).use {
            runBlocking {
                val container = (context.applicationContext as HelixApplication).appContainer
                val profile = container.profileStore.profile
                val terminal = requireNotNull(container.manualTerminal)
                val relative = "first-use-${UUID.randomUUID()}"
                val directory = File(context.filesDir, "workspaces/app/terminal/$relative").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    terminal.start(relative, 120_000)
                    val connection = terminal.attach()
                    try {
                        assertEquals(
                            "ACCEPTED",
                            connection.write("printf HXA305_TERMINAL > actual.txt\n".toByteArray()),
                        )
                        withTimeout(30_000) {
                            while (File(
                                    directory,
                                    "actual.txt",
                                ).let { !it.isFile || it.readText() != "HXA305_TERMINAL" }
                            ) {
                                delay(50)
                            }
                        }
                    } finally {
                        connection.detach()
                    }
                } finally {
                    if (terminal.hasSession()) {
                        terminal.stop()
                        withTimeout(15_000) {
                            while (!terminal.query().canSettle) delay(50)
                        }
                        terminal.settle()
                    }
                    container.profileStore.switchTo(profile)
                    directory.deleteRecursively()
                }
            }
        }
    }
}
