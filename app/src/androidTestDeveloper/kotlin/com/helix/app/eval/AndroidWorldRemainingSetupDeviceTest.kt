package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, separately authorized setup for an isolated benchmark emulator only. */
class AndroidWorldRemainingSetupDeviceTest {
    @Test
    fun configureAuthorizedScope() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("helix.authorizedBenchmarkScope") == "true")
        val expectedAvd = requireNotNull(args.getString("avdName"))
        require(expectedAvd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val actualAvd =
            automation.executeShellCommand("getprop ro.boot.qemu.avd_name").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()).trim() }
            }
        check(actualAvd == expectedAvd) { "Only the explicitly designated benchmark emulator is permitted" }
        val packages = requireNotNull(args.getString("packages")).split(',').toSet()
        require(
            packages.isNotEmpty() &&
                packages.all { it == "android" || it.matches(Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) },
        )
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        check(
            container.storage.turns
                .listActive()
                .isEmpty(),
        ) { "Active turns must settle before setup" }
        val catalog = container.pluginService.catalog
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val grants = MobileUseGrantStore(lines::lines, lines::setLines, { catalog.selectionId(it, "mobile-use") })
        grants.configureExecution(background = false, allowMoveFromMain = false)
        grants.configureGlobal(packages, wholePhone = false, shareScreens = true)
        check(grants.globalConfiguration()?.scope?.allowedPackages == packages)
    }
}
