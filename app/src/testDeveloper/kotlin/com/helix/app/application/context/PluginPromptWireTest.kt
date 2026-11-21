package com.helix.app.application.context

import com.helix.app.application.tools.ToolModelSchemaProjection
import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.mcp.McpToolDiscovery
import com.helix.core.agent.HeadlessLoopFixture
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelEvent
import com.helix.core.workspace.FileScopePath
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.extensions.mobileuse.tools.AutomationTools
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.NativePluginCatalog
import com.helix.extensions.plugin.NativePluginState
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.extensions.plugin.PluginRegistry
import com.helix.extensions.plugin.presentationContributions
import com.helix.extensions.plugin.skillPresentation
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import com.helix.provider.openai.chat.ChatCompletionsRequestEncoder
import com.helix.tools.files.EditTool
import com.helix.tools.files.ReadTool
import com.helix.tools.files.WriteTool
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Production projection, loop request and provider encoder; no Android calls or model network. */
class PluginPromptWireTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mobileSelectionAndReadinessControlGuidanceAndWireTools() =
        runBlocking {
            val catalog = Catalog()
            val tools = ToolRegistry()
            val plugins = PluginRegistry(tools, catalog)
            val mobile = MobileFixture()
            plugins.register(mobile)
            val root = temporary.root.toPath()
            val skills =
                SkillRepository(
                    root.resolve("snapshots"),
                    root.resolve("state"),
                    root.resolve("trash"),
                    builtIns = emptyList(),
                    sourceAvailable = plugins::skillAvailable,
                    contributedDocuments = plugins::skillDocuments,
                    metadataLock = catalog.mutationLock,
                )
            val discovery = McpToolDiscovery(tools, plugins::modelAvailability)
            val skillKey = skills.list("s").single().key
            for (state in listOf("selected", "unselected", "disabled", "not-ready", "restored")) {
                catalog.selected = state != "unselected"
                mobile.failure = null
                plugins.setEnabled(mobile.manifest.name, state != "disabled")
                mobile.failure = if (state == "not-ready") "FIXTURE_UNAVAILABLE" else null
                val native = plugins.nativeContentSnapshot("s")
                val content =
                    presentationContributions(
                        native,
                        skills.presentationSnapshot("s", emptyList(), native.skillPresentation()),
                    )
                val visible = discovery.visible("s", tools.all(), tools.all().map { it.name.value }.toSet())
                val names = visible.map { it.name.value }.toSet()
                discovery.search("s", "ui_snapshot", 1) // Retained matches must leave on the next unavailable request.
                val request = SystemPromptRequest("s", "t", FileScopePath("app", "work/test"), AgentMode.ACT, names)
                val snapshot =
                    SystemPromptContext(packagedPromptTemplates).build(
                        request,
                        SystemPromptFacts(request),
                        PromptContentContributions.sections(content, null, null, names),
                    )
                val loop = HeadlessLoopFixture("t") { listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")) }
                loop.tools = visible.map(ToolModelSchemaProjection::project)
                loop.journal.history.addAll(0, snapshot.modelMessages())
                loop.loop().runToolLoop("s", loop.journal, "p", null, loop.control)
                val encoded = ChatCompletionsRequestEncoder { error("No images") }.encode(loop.requests.single())
                val wire = Json.parseToJsonElement(encoded).jsonObject
                val system = systemContent(wire)
                val enabled = state in setOf("selected", "restored")
                assertEquals(state, enabled, "[MOBILE_USE]" in system)
                assertEquals(state, enabled, "[SKILL CATALOG]" in system)
                assertFalse(system.contains("# Android UI task"))
                assertEquals(state, enabled, wire["tools"]?.jsonArray?.isNotEmpty() == true)
                assertFalse(encoded.contains("Observation chooses ready Root"))
                assertSkillEligibility(skills, skillKey, enabled)
                if (enabled) assertMobileCallContract(wire, system)
                assertTrue(loop.dispatched.isEmpty())
            }
        }

    private fun systemContent(wire: JsonObject): String {
        val message =
            wire
                .getValue("messages")
                .jsonArray
                .first()
                .jsonObject
        return message.getValue("content").jsonPrimitive.content
    }

    private fun assertSkillEligibility(
        skills: SkillRepository,
        key: SkillKey,
        enabled: Boolean,
    ) {
        val reference = "references/android-ui-reference.md"
        if (enabled) {
            val document = skills.read(key, "s", "t")
            assertTrue(document.body.contains("Leave backend selection automatic"))
            val details = requireNotNull(skills.readResource(key, reference, "s", "t").content)
            assertTrue(details.contains("Use ui_device and current tool results"))
            assertFalse(details.contains("host prefers ready Root"))
            assertTrue(skills.listForModel("s", "t").single().enabled)
        } else {
            assertTrue(skills.listForModel("s", "t").none { it.enabled })
            assertThrows(IllegalArgumentException::class.java) { skills.read(key, "s", "t") }
            assertThrows(IllegalArgumentException::class.java) { skills.readResource(key, reference, "s", "t") }
        }
    }

    private fun assertMobileCallContract(
        wire: JsonObject,
        system: String,
    ) {
        assertEquals(1, Regex("\\[MOBILE_USE]").findAll(system).count())
        val descriptions =
            wire.getValue("tools").jsonArray.associate {
                val function = it.jsonObject.getValue("function").jsonObject
                function.getValue("name").jsonPrimitive.content to
                    function.getValue("description").jsonPrimitive.content
            }
        assertTrue(descriptions.getValue("ui_snapshot").contains("replaces earlier tokens"))
        assertTrue(descriptions.getValue("ui_set_text").contains("canImeEnter=true"))
        assertTrue(descriptions.getValue("ui_click").contains("verify the effect"))
        assertTrue(system.contains("that screenshot's returned frame"))
        assertTrue(system.contains("never automatic replay"))
    }

    @Test fun fileWireKeepsCrossToolGuardWithoutDuplicatingReadLimits() =
        runBlocking {
            val descriptors = listOf(ReadTool.descriptor(), WriteTool.descriptor(), EditTool.descriptor())
            val names = descriptors.map { it.name.value }.toSet()
            val request = SystemPromptRequest("s", "t", FileScopePath("app", "work/test"), AgentMode.ACT, names)
            val snapshot = SystemPromptContext(packagedPromptTemplates).build(request, SystemPromptFacts(request))
            val loop = HeadlessLoopFixture("t") { listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")) }
            loop.tools = descriptors.map(ToolModelSchemaProjection::project)
            loop.journal.history.addAll(0, snapshot.modelMessages())
            loop.loop().runToolLoop("s", loop.journal, "p", null, loop.control)
            val outgoing = loop.requests.single()
            val system = outgoing.messages.first().text
            assertTrue(system.contains("On a hash conflict, reread and reconcile"))
            assertTrue(system.contains("Do not reconstruct a whole file from a partial read"))
            assertFalse(system.contains("32 MiB"))
            assertTrue(
                outgoing.tools
                    .single { it.name.value == "read" }
                    .description
                    .contains("32 MiB"),
            )
            assertTrue(
                outgoing.tools
                    .single { it.name.value == "edit" }
                    .description
                    .contains("includeSha256=true"),
            )
            val wire = ChatCompletionsRequestEncoder { error("No images") }.encode(outgoing)
            assertTrue(wire.contains("expectedSha256"))
        }

    private class Catalog : NativePluginCatalog {
        override val mutationLock = Any()
        private var state: NativePluginState? = null
        var selected = true

        override fun selectionId(
            sessionId: String,
            pluginId: String,
        ) = if (selected) "selection" else null

        override fun find(pluginId: String) = state

        override fun publish(
            state: NativePluginState,
            expectedRevision: Long?,
        ) {
            check(this.state?.revision == expectedRevision)
            this.state = state
        }
    }

    private class MobileFixture : HelixPlugin {
        private val assets =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("extensions/mobile-use/src/main/assets/plugins/mobile-use") }
                .first(Files::isDirectory)
        override val manifest = PluginManifestReader.parse(Files.readAllBytes(assets.resolve("plugin.json")))
        override val requiredInstructions = Files.readString(assets.resolve("required.md"))
        override val skills =
            listOf(
                SkillLoader().loadBuiltIn(
                    Files.readString(assets.resolve("skills/android-ui-task/SKILL.md")),
                    "android-ui-task",
                    listOf("android-ui-reference.md", "vision-and-gestures.md", "completion-and-display.md")
                        .associate { name ->
                            "references/$name" to
                                Files.readString(assets.resolve("skills/android-ui-task/references/$name"))
                        },
                ),
            )
        var failure: String? = null

        override fun registrationError() = failure

        private val port =
            object : AutomationToolPort {
                override fun snapshot() = error("No device execution")

                override fun nodeAction(request: AutomationNodeActionRequest) = error("No device execution")

                override fun globalAction(action: AutomationGlobalAction) = error("No device execution")
            }
        private val automation =
            AutomationTools(port, ToolOrigin.PluginOrigin(manifest.name, manifest.version, manifest.helixRuntimeId!!))

        override fun tools() = automation.descriptors().map { ToolBinding(it, automation.executor(it.name.value)) }
    }
}
