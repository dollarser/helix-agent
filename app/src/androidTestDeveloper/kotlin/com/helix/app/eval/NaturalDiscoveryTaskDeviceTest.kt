package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.proot.diagnosticJson
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Real AgentRuntime and request assembler; only owned synthetic workspace effects are admitted. */
class NaturalDiscoveryTaskDeviceTest {
    @Test
    @Suppress("LongMethod") // Three bounded tasks, independently verified effects and durable evidence.
    fun naturalTasksUseProductionDiscoveryAndPreserveDeniedEffects(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("naturalDiscovery")
            assumeTrue("Explicit live authorization absent", flag != null)
            require(flag == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            require(app.packageName.endsWith(".asrvalidation"))
            val c = app.awaitAppContainer()
            assertTrue("Account restoration must complete before starting model tasks", app.account.signedIn.value)
            val evidence = File(app.cacheDir, "natural-discovery-tasks.jsonl").apply { writeText("") }
            val outcomes = mutableListOf<Boolean>()
            for (index in 0..2) {
                val writable = index != 2
                val marker = "SYNTH_${UUID.randomUUID().toString().replace("-", "") }"
                val relative = "work/natural-$marker"
                val root = File(app.filesDir, "workspaces/app/$relative")
                val source = File(root, "source").apply { check(mkdirs()) }
                File(source, "marker.txt").writeText(marker)
                val target = File(root, "work/result.zip")
                val session =
                    c.chatService.createSession(
                        "Synthetic natural discovery",
                        BuiltInModel.PROVIDER_ID,
                        BuiltInModel.MODEL_ID,
                    )
                val tools =
                    c.toolPipeline.registry
                        .all()
                        .distinctBy { it.origin.canonicalOf() to it.name }
                val allowed =
                    setOf("tools.search", "tools.read_result", "files.archive", "files.list", "files.stat", "read")
                val disabled = tools.filter { it.name.value !in allowed }
                var turn: String? = null
                try {
                    check(c.chatService.setSessionDirectory("scope:app:$relative", session).await())
                    val directory =
                        requireNotNull(
                            c.storage.sessions
                                .resolve(session)
                                .directoryRef,
                        ).removeSuffix(".")
                    disabled.forEach {
                        c.storage.toolAvailability.set(
                            it.origin.canonicalOf(),
                            it.name.value,
                            ToolAvailabilityScope.SESSION,
                            session,
                            ToolAvailabilityState.DISABLED,
                            System.currentTimeMillis(),
                        )
                    }
                    c.storage.sessionPermissionConfigs.setForSession(
                        session,
                        fixturePermissions(writable),
                        System.currentTimeMillis(),
                    )
                    val input = "${directory}source"
                    val output = "${directory}work/result.zip"
                    val prompt =
                        if (index == 1) {
                            "Pack the folder $input into one ZIP at $output, keeping the source unchanged. " +
                                "Stay inside that folder and do not run commands or access the network."
                        } else {
                            "请把目录 $input 打包为一个 ZIP，保存到 $output，保持源文件不变。" +
                                "只操作这份合成资料，不运行命令，不访问网络。"
                        }
                    turn =
                        c.agentRuntime
                            .submit(
                                SubmitTurnCommand(
                                    SessionId(session),
                                    ProviderId(BuiltInModel.PROVIDER_ID),
                                    AgentMode.ACT,
                                    prompt,
                                    budgets = TurnBudgets(2048),
                                    clientRequestId = UUID.randomUUID().toString(),
                                    directUserRequest = true,
                                ),
                            ).value
                    val id = requireNotNull(turn)
                    val boundedStop = awaitFixtureTurn(c, id)
                    val record = c.storage.turns.resolve(id)
                    val calls = c.storage.toolCalls.listByTurn(id)
                    val modelCalls = c.storage.modelCalls.listByTurn(id)
                    val archives = calls.count { it.name == "files.archive" && it.state == "COMPLETED" }
                    val verified = verifiesArchive(target, marker)
                    val intact = File(source, "marker.txt").readText() == marker
                    val pass =
                        intact &&
                            effectPassed(
                                writable,
                                target,
                                verified,
                                archives,
                                !boundedStop && record.state == "COMPLETED",
                            )
                    outcomes += pass
                    evidence.appendText(
                        buildJsonObject {
                            put("case", index)
                            put("writable", writable)
                            put("prompt", prompt)
                            put("state", record.state)
                            put("errorCode", record.errorCode)
                            put("boundedStop", boundedStop)
                            put("effectVerified", verified)
                            put("sourceIntact", intact)
                            put("passed", pass)
                            put("modelCalls", modelCalls.size)
                            if (writable) put("extraCalls", (calls.size - 2).coerceAtLeast(0))
                            put("successfulArchives", archives)
                            put(
                                "catalog",
                                c.toolPipeline.mcpDiscovery
                                    .catalog(session)
                                    .diagnosticJson(),
                            )
                            put(
                                "calls",
                                JsonArray(
                                    calls.map { call ->
                                        buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.argsJson)
                                            put("state", call.state)
                                            put(
                                                "result",
                                                c.storage.toolResults
                                                    .byToolCall(call.id)
                                                    ?.let(c.storage.toolResults::readContent),
                                            )
                                        }
                                    },
                                ),
                            )
                            put("promptSections", JsonArray(modelCalls.map { JsonPrimitive(it.promptSections) }))
                        }.toString() + "\n",
                    )
                } finally {
                    turn?.let(c.chatService::stopTask)
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    // Retain synthetic evidence and restrictive rows until the owned package is removed.
                }
            }
            assertTrue(
                "Inspect all preserved cases; task effects, not model claims, must pass",
                outcomes.size == 3 && outcomes.all { it },
            )
        }

    private fun effectPassed(
        writable: Boolean,
        target: File,
        verified: Boolean,
        archives: Int,
        completed: Boolean,
    ): Boolean = if (writable) completed && verified && archives == 1 else !target.exists() && archives == 0

    private suspend fun awaitFixtureTurn(
        c: com.helix.app.AppContainer,
        id: String,
    ): Boolean {
        var boundedStop = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
        while (c.storage.turns
                .resolve(id)
                .state !in terminalStates
        ) {
            if (c.storage.modelCalls
                    .listByTurn(id)
                    .size >= 6 || android.os.SystemClock.elapsedRealtime() >= deadline
            ) {
                boundedStop = true
                c.chatService.stopTask(id)
                break
            }
            delay(100)
        }
        if (boundedStop) {
            withTimeout(15_000) {
                while (c.storage.turns
                        .resolve(id)
                        .state !in terminalStates
                ) {
                    delay(50)
                }
            }
        }
        return boundedStop
    }

    private fun fixturePermissions(writable: Boolean): SessionPermissionConfig =
        SessionPermissionConfig.custom(
            OperationEffect.values().associateWith { effect ->
                when (effect) {
                    OperationEffect.FILE_READ_WORKSPACE -> OperationRule.ALLOW
                    OperationEffect.FILE_MUTATION_WORKSPACE -> if (writable) OperationRule.ALLOW else OperationRule.DENY
                    else -> OperationRule.DENY
                }
            },
        )

    private fun verifiesArchive(
        target: File,
        marker: String,
    ): Boolean =
        target.isFile &&
            runCatching {
                ZipFile(target).use { zip ->
                    val entries = zip.entries().toList().filterNot { it.isDirectory }
                    entries.size == 1 &&
                        zip.getInputStream(entries.single()).bufferedReader().use { it.readText() } == marker
                }
            }.getOrDefault(false)

    private val terminalStates = setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")
}
