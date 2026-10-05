package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Explicit cleanup of test-owned selections after an interrupted instrumentation process. */
class CapabilityCleanupDeviceTest {
    @Test fun clearOwnedSelections() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val optIn = args.getString("helixCleanup") ?: "false"
            require(optIn in setOf("true", "false")) { "Invalid helixCleanup value" }
            org.junit.Assume.assumeTrue(
                "Explicit opt-in required: helixCleanup",
                optIn == "true",
            )
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val trials = requireNotNull(args.getString("helixTrials")).split(',').toSet()
            val catalog = c.pluginService.catalog
            val plugin = catalog.list().single { it.native?.pluginId == "mobile-use" }
            val owned =
                c.storage.sessions.list().filter { session ->
                    trials.any { session.title.startsWith("能力评测 $it/") }
                }
            owned.forEach { catalog.select(it.id, plugin.id, false) }
            check(owned.all { plugin.id !in catalog.selected(it.id) })
            c.chatService.openSession(requireNotNull(args.getString("helixOriginalSession")))
            println("CAPABILITY_CLEANUP_SESSIONS=${owned.size}")
        }
}
