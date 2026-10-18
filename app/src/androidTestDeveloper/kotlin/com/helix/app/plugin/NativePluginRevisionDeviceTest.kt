package com.helix.app.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.storage.HelixStorage
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.extensions.plugin.PluginRegistry
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Uses the Advanced native implementation, with isolated Room fixtures and read-only app facts. */
class NativePluginRevisionDeviceTest {
    @Test fun packagedPluginsAndCurrentToolsUsePreviewVersionOne() {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        assertTrue(container.pluginRegistry.all().isNotEmpty())
        assertTrue(container.pluginRegistry.all().all { it.version == "1" })
        val nativeIds =
            container.pluginRegistry
                .all()
                .map { it.name }
                .toSet()
        val unexpected =
            container.toolPipeline.registry
                .all()
                .filter { it.version.value != 1 }
        val diagnostic =
            unexpected.joinToString { descriptor ->
                val origin = descriptor.origin
                val hostOwned =
                    origin == ToolOrigin.BuiltInOrigin ||
                        (origin is ToolOrigin.PluginOrigin && origin.pluginId in nativeIds)
                val name = if (hostOwned) descriptor.name.value else "external"
                "$name:${descriptor.version.value}:${origin::class.simpleName}"
            }
        assertTrue(diagnostic, unexpected.isEmpty())
    }

    @Test fun realRoomRestartKeepsRevisionSelectionGrantAndNormalizedDisable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "native-revision-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val storage = HelixStorage.open(context, name, File(directory, "content"))
        try {
            storage.toolAvailability.set(
                "plugin:mobile-use:0.2.0:mobile-use",
                "fixture.read",
                ToolAvailabilityScope.GLOBAL,
                "",
                ToolAvailabilityState.DISABLED,
                1,
            )
            val catalog = PluginCatalog(storage)
            val native = RoomNativePluginCatalog(catalog)
            val firstTools = ToolRegistry("host-1")
            val first = PluginRegistry(firstTools, native)
            first.register(plugin())
            storage.sessions.create("fixture", "Fixture", null, null, 1)
            catalog.select("fixture", "bundled-plugin:mobile-use", true)
            val preferences = InMemoryLineStore()

            fun grants() =
                MobileUseGrantStore(
                    preferences::lines,
                    preferences::setLines,
                    { session -> native.selectionId(session, "mobile-use") },
                )
            val grants = grants()
            grants.configureGlobal(setOf("com.fixture.app"), false)
            val grant = grants.find("fixture")
            val initial = native.find("mobile-use")
            val selection = native.selectionId("fixture", "mobile-use")
            val oldRef = firstTools.snapshot().single().ref
            val restartedTools = ToolRegistry("host-2")
            val restarted = PluginRegistry(restartedTools, RoomNativePluginCatalog(PluginCatalog(storage)))
            restarted.register(plugin())
            assertEquals(initial, native.find("mobile-use"))
            assertNotEquals(
                oldRef.incarnation,
                restartedTools
                    .snapshot()
                    .single()
                    .ref.incarnation,
            )
            assertEquals(selection, native.selectionId("fixture", "mobile-use"))
            assertEquals(grant, grants().find("fixture"))
            restarted.repair("mobile-use")
            assertEquals(initial, native.find("mobile-use"))
            assertNormalizedDisable(storage)
            restartedTools.replaceOwner(oldRef.owner, emptyList())
            restarted.repair("mobile-use")
            assertEquals(initial!!.revision + 1, native.find("mobile-use")!!.revision)
            assertEquals(grant, grants().find("fixture"))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    private fun assertNormalizedDisable(storage: HelixStorage) {
        assertTrue(storage.toolAvailability.byTool("plugin:mobile-use:0.2.0:mobile-use", "fixture.read").isEmpty())
        assertEquals(
            ToolAvailabilityState.DISABLED,
            storage.toolAvailability.statesFor("plugin:mobile-use:mobile-use", "fixture.read", "fixture", null).global,
        )
    }

    private fun plugin(): HelixPlugin {
        val manifest =
            PluginManifestReader.parse(
                """
                {
                  "${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
                  "name":"mobile-use", "version":"1",
                  "extensions":{"com.helix.agent":{"runtime":"mobile-use"}}
                }
                """.trimIndent().toByteArray(),
            )
        val descriptor =
            ToolDescriptor(
                ToolName("fixture.read"),
                ToolVersion(1),
                "Read fixture",
                buildJsonObject {},
                buildJsonObject {},
                ToolOperationClass.READ_ONLY,
                1.seconds,
                1024,
                emptySet(),
                Idempotency.IDEMPOTENT,
                ExecutionTargetType.LOCAL_ANDROID,
                ToolOrigin.PluginOrigin("mobile-use", "1", "mobile-use"),
            )
        return object : HelixPlugin {
            override val manifest = manifest

            override fun tools() =
                listOf(
                    ToolBinding(
                        descriptor,
                        object : ToolExecutor {
                            override fun execute(call: com.helix.tools.framework.ExecutableToolCall) =
                                ToolExecutorResult.Completed(buildJsonObject {})
                        },
                    ),
                )
        }
    }
}
