package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.workspace.FileScopePath
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** An owned synthetic workspace exercises discovery, failure recovery and independently checked output. */
class PluginFileWorkflowModelDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    @Suppress("LongMethod") // One bounded model journey with explicit fixture ownership and cleanup.
    fun selectedWorkflowRecoversMissingInputAndVerifiesSavedTotals() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "File workflow fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                        CleartextWarning("127.0.0.1", port),
                        emptyList(),
                    ),
                    null,
                )
            val name = "file-flow-${UUID.randomUUID()}"
            val bundle =
                PluginPackageReader().parse(
                    mapOf(
                        "plugin.json" to
                            (
                                "{\"\$schema\":\"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json\"," +
                                    "\"name\":\"$name\",\"version\":\"1.0.0\"," +
                                    "\"description\":\"File workflow fixture\"}"
                            ).toByteArray(),
                        "skills/$name/SKILL.md" to
                            (
                                "---\nname: $name\n" +
                                    "description: Summarize synthetic regional orders and returns\n---\n" +
                                    "# Regional totals\nRead the primary orders file first. If it is absent, " +
                                    "read the supplied fallback orders path. Read returns. " +
                                    "Sum orders minus returns " +
                                    "for each region. Save a JSON object containing East, West " +
                                    "and total integer values. " +
                                    "Read the saved file back before reporting completion. Never invent missing input."
                            ).toByteArray(),
                    ),
                )
            var installedId: String? = null
            var session: String? = null
            var directory: File? = null
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val installed = c.pluginService.install(bundle, "fixture:$name")
                installedId = installed.id
                val id = c.chatService.createSession("File workflow fixture", provider, model)
                session = id
                val ref =
                    FileScopePath.fromModelReference(
                        c.storage.sessions
                            .resolve(id)
                            .directoryRef!!,
                    )
                val files =
                    c.storage.workspaces
                        .managedDirectory(ref.scopeId)
                        .resolve(ref.relativePath)
                        .toFile()
                directory = files
                files.mkdirs()
                File(files, "orders.csv").writeText("region,amount\nEast,120\nWest,80\nEast,30\nWest,40\n")
                File(files, "returns.csv").writeText("region,amount\nEast,10\nWest,20\n")
                c.sessionPermissionEdit.saveSessionConfig(
                    id,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.pluginService.setSkillEnabled(installed.skills.single(), true)
                c.pluginService.selectFromUser(id, installed.id, true)
                c.chatService.openTestSessionControl(id, AgentMode.ACT, TurnBudgets(24, 20, 524288, 32768, 557056))
                val relativePrefix =
                    ref.relativePath
                        .takeIf { it.isNotEmpty() }
                        ?.plus("/")
                        .orEmpty()
                val prefix = "scope:${ref.scopeId}:$relativePrefix"
                c.chatService.sendTestMessage(
                    id,
                    "Run the selected $name workflow: discover it with skills.list and read its exact Skill. " +
                        "Discover file tools with tools.search. Primary orders=${prefix}missing-orders.csv; " +
                        "first try reading that primary path, then recover using fallback=${prefix}orders.csv. " +
                        "Returns=${prefix}returns.csv. Save the JSON totals to ${prefix}totals.json " +
                        "and read it back. " +
                        "Use tools for all file operations; only this synthetic workspace is authorized.",
                )
                val deadline = android.os.SystemClock.elapsedRealtime() + 240000
                while (android.os.SystemClock.elapsedRealtime() < deadline &&
                    c.storage.turns
                        .listBySession(id)
                        .lastOrNull()
                        ?.let { TurnState.valueOf(it.state).isTerminal } !=
                    true
                ) {
                    delay(250)
                }
                val turn =
                    c.storage.turns
                        .listBySession(id)
                        .last()
                assertEquals(turn.errorCode, "COMPLETED", turn.state)
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                println("PLUGIN_FILE_TRAJECTORY=" + calls.joinToString { "${it.name}:${it.state}:${it.argsJson}" })
                verifyDiscoveryAndRecovery(calls, prefix)
                val write =
                    calls.indexOfLast {
                        it.name == "write" && it.state == "COMPLETED" &&
                            it.argsJson.contains("totals.json")
                    }
                assertTrue("Missing output write", write >= 0)
                assertTrue(
                    calls.drop(write + 1).any {
                        it.name == "read" && it.state == "COMPLETED" &&
                            it.argsJson.contains("totals.json")
                    },
                )
                val result = Json.parseToJsonElement(File(files, "totals.json").readText()).jsonObject
                assertEquals(setOf("East", "West", "total"), result.keys)
                assertEquals("140", result.getValue("East").jsonPrimitive.content)
                assertEquals("100", result.getValue("West").jsonPrimitive.content)
                assertEquals("240", result.getValue("total").jsonPrimitive.content)
                println("PLUGIN_FILE_WORKFLOW=PASS calls=${calls.size} missingInputRecovered=true outputVerified=true")
            } finally {
                session?.let { id ->
                    c.storage.turns
                        .listBySession(id)
                        .lastOrNull()
                        ?.let { c.chatService.stopTurn(it.id) }
                    c.chatService.closeSession()
                    c.storage.deleteSessionPermanently(id)
                }
                c.pluginService
                    .list()
                    .filter { it.id == installedId }
                    .forEach(c.pluginService::remove)
                directory?.deleteRecursively()
                c.providerService.delete(provider)
            }
        }

    private fun verifyDiscoveryAndRecovery(
        calls: List<ToolCallEntity>,
        prefix: String,
    ) {
        for (tool in listOf("skills.list", "skills.read", "tools.search")) {
            assertTrue("Missing successful $tool", calls.any { it.name == tool && it.state == "COMPLETED" })
        }
        assertTrue(
            "Missing failed primary input read",
            calls.any {
                it.name == "read" && it.state == "FAILED" && it.argsJson.contains("${prefix}missing-orders.csv")
            },
        )
        for (input in listOf("orders.csv", "returns.csv")) {
            assertTrue(
                "Missing successful read of $input",
                calls.any { it.name == "read" && it.state == "COMPLETED" && it.argsJson.contains("$prefix$input") },
            )
        }
    }
}
