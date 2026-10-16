package com.helix.app.plugin

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.extensions.plugin.PluginMeasurement
import com.helix.extensions.plugin.preferredTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded daily-device measurements; timings are evidence, never readiness or pass thresholds. */
class PluginDiagnosticsDeviceTest {
    @Test
    fun nativeMeasurementsAreAvailableWithoutActivatingPluginsOrTools() =
        runBlocking {
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val before = c.pluginService.list()
            val plugins = c.pluginRegistry.all()
            assertTrue(plugins.isNotEmpty())
            val timings =
                (1..20).map {
                    val start = SystemClock.elapsedRealtimeNanos()
                    c.pluginService.list()
                    SystemClock.elapsedRealtimeNanos() - start
                }
            val unselected = "diagnostic-${java.util.UUID.randomUUID()}"
            val contextTimings =
                (1..20).map {
                    val start = SystemClock.elapsedRealtimeNanos()
                    assertTrue(c.pluginRegistry.preferredTools(unselected).isEmpty())
                    SystemClock.elapsedRealtimeNanos() - start
                }
            c.toolPipeline.registry.all().forEach { descriptor ->
                val schema =
                    com.helix.app.chat.FileToolArguments
                        .modelSchema(descriptor)
                com.helix.app.chat.ToolPresentationMetadata
                    .augment(schema)
            }
            plugins.forEach { c.pluginRegistry.ready(it.name) }
            assertTrue(
                c.pluginRegistry.performance.snapshot().any {
                    it.operation == PluginMeasurement.REGISTRATION && it.calls > 0
                },
            )
            assertTrue(
                c.pluginRegistry.performance.snapshot().any {
                    it.operation == PluginMeasurement.FINGERPRINT && it.calls > 0
                },
            )
            val report = Json.parseToJsonElement(requireNotNull(c.diagnosticReport).preview()).jsonObject
            assertTrue("pluginPerformance" in report)
            assertTrue(c.pluginService.list() == before)
            println("PLUGIN_LIST_NANOS=" + timings.joinToString(","))
            println("PLUGIN_UNSELECTED_CONTEXT_NANOS=" + contextTimings.joinToString(","))
            println("PLUGIN_PERFORMANCE=" + c.pluginRegistry.performance.json())
        }
}
