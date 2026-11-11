package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.agent.ContextCompaction
import com.helix.app.provider.ProviderDraft
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in real provider and Dispatcher acceptance, with only session-owned synthetic files. */
class AutoContextLongTaskDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val terminal = setOf("COMPLETED", "FAILED", "CANCELLED", "NEEDS_REVIEW", "INTERRUPTED")

    @Test
    @Suppress("LongMethod") // A paired live-provider lifecycle retains all evidence before cleanup.
    fun automaticCompactionContinuesRealFileTask() =
        runBlocking {
            require(InstrumentationRegistry.getArguments().getString("helixAutoContext") == "true")
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "Owned auto context",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://10.0.2.2:30018/v1"),
                        "Qwen3.8-27B",
                        "{}",
                        false,
                    ),
                    null,
                )
            val all = JSONArray()
            try {
                check(c.providerService.runConnectionTest(provider) is com.helix.provider.api.ProbeOutcome.Ok)
                for (enabled in listOf(false, true)) {
                    c.providerService.saveContextSettings(
                        provider,
                        "Qwen3.8-27B",
                        ProviderContextSettings(manualWindow = 98304, autoCompact = enabled, triggerPercent = 80),
                    )
                    val session = c.chatService.createSession("Owned long task auto=$enabled", provider, "Qwen3.8-27B")
                    val binding = requireNotNull(c.storage.workspaces.binding(session))
                    val root =
                        c.storage.workspaces
                            .managedDirectory(
                                binding.workspaceId,
                            ).resolve(binding.relativePath)
                            .toFile()
                    val original =
                        (0..7).associate { i ->
                            val name = "input/part-$i.txt"
                            val text =
                                "Record $i code=ITEM-$i quantity=${i + 1} servings.\n" +
                                    (1..225).joinToString("\n") { n ->
                                        "Log $n for record $i: routine check OK; no change to record " +
                                            "quantities, " +
                                            "task constraints or results. Synthetic diagnostic detail only."
                                    }
                            File(root, name).apply {
                                parentFile!!.mkdirs()
                                writeText(text)
                            }
                            name to text
                        }
                    c.sessionPermissionEdit.saveSessionConfig(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                        System.currentTimeMillis(),
                    )
                    val evidence =
                        JSONObject()
                            .put("autoCompact", enabled)
                            .put("session", session)
                            .put("triggerPercent", 80)
                            .put("manualWindow", 98304)
                            .put("logLinesPerFile", 225)
                    val turns = JSONArray()
                    try {
                        for (i in 0..7) {
                            val intro =
                                when (i) {
                                    0 -> {
                                        "Prepare a local inventory draft only, never send or upload. Budget 480" +
                                            " CNY and sample " +
                                            "volume 12.50 mL. Preserve all inputs. "
                                    }

                                    2 -> {
                                        "Correction: budget is now 360 CNY and sample volume 125.0 μL, not the " +
                                            "previous values. All" +
                                            " other restrictions remain. "
                                    }

                                    4 -> {
                                        "First attempt to read input/missing.txt once; it intentionally does " +
                                            "not exist. Preserve " +
                                            "this failed-read fact, never invent its contents. "
                                    }

                                    else -> {
                                        "Continue the same inventory task and retain prior constraints. "
                                    }
                                }
                            val prompt =
                                intro + "Read the entire input/part-$i.txt with read tools, including its " +
                                    "diagnostic log lines, " +
                                    "then append its exact record code and quantity to output/draft.txt. " +
                                    "Use only this session workspace. Do not summarize away the file " +
                                    "without reading it. " +
                                    "Reply briefly when done."
                            val turn = submit(session, provider, prompt)
                            await(turn)
                            turns.put(snapshot(turn))
                            val state =
                                c.storage.turns
                                    .resolve(turn)
                                    .state
                            println("AUTO_CONTEXT_PROGRESS auto=$enabled index=$i state=$state")
                            check(
                                c.storage.turns
                                    .resolve(turn)
                                    .state == "COMPLETED",
                            ) { "File turn $i failed: ${c.storage.turns.resolve(turn)}" }
                        }
                        val cancel =
                            submit(
                                session,
                                provider,
                                "Inspect the diagnostic files again and draft a very detailed 10000 " +
                                    "word analysis. Do not " +
                                    "send or upload anything.",
                            )
                        withTimeout(30_000) {
                            while (c.storage.modelCalls
                                    .listByTurn(cancel)
                                    .isEmpty()
                            ) {
                                delay(50)
                            }
                        }
                        delay(5_000)
                        c.chatService.stopTask(cancel)
                        await(cancel)
                        turns.put(snapshot(cancel))
                        val finalTurn =
                            submit(
                                session,
                                provider,
                                "Cancel the detailed analysis permanently. Keep the inventory draft, do" +
                                    " not resume that " +
                                    "cancelled request. Write output/final.txt with the current budget and " +
                                    "sample volume " +
                                    "exactly, all eight record codes and quantities from the draft, whether" +
                                    " the missing file " +
                                    "read succeeded or failed, the prohibition on sending/uploading, and " +
                                    "whether the last " +
                                    "analysis was completed or cancelled. Verify the saved output by " +
                                    "reading it. Do not claim " +
                                    "cancellation rolled back any effects.",
                            )
                        await(finalTurn)
                        turns.put(snapshot(finalTurn))
                        val output = File(root, "output/final.txt").takeIf { it.exists() }?.readText().orEmpty()
                        val checkpoints =
                            c.storage.messages.listBySession(session).filter {
                                it.kind ==
                                    ContextCompaction.KIND
                            }
                        val preserved = original.all { (name, text) -> File(root, name).readText() == text }
                        val pass =
                            verifiesOutput(output, preserved, cancel, finalTurn) &&
                                (!enabled || checkpoints.isNotEmpty())
                        evidence
                            .put("output", output)
                            .put("inputsPreserved", preserved)
                            .put("passed", pass)
                            .put("checkpointCount", checkpoints.size)
                            .put(
                                "checkpoints",
                                JSONArray(
                                    checkpoints.map {
                                        c.storage.messages.readContent(it)
                                    },
                                ),
                            )
                    } finally {
                        evidence.put("turns", turns)
                        all.put(evidence)
                        File(app.filesDir, "auto-context-result.json").writeText(all.toString(2))
                        println("AUTO_CONTEXT_EVIDENCE_SAVED auto=$enabled")
                        c.storage.turns
                            .listBySession(session)
                            .forEach { c.chatService.stopTask(it.id) }
                        c.storage.sessions.archive(session, System.currentTimeMillis())
                    }
                }
                assertTrue(all.toString(2), (0 until all.length()).all { all.getJSONObject(it).optBoolean("passed") })
            } finally {
                c.providerService.delete(provider)
            }
        }

    private fun verifiesOutput(
        output: String,
        preserved: Boolean,
        cancel: String,
        finalTurn: String,
    ): Boolean =
        output.contains("360") && output.contains("125.0") &&
            (0..7).all { output.contains("ITEM-$it") } && preserved &&
            c.storage.turns
                .resolve(cancel)
                .state == "CANCELLED" &&
            c.storage.turns
                .resolve(finalTurn)
                .state == "COMPLETED"

    private suspend fun submit(
        session: String,
        provider: String,
        prompt: String,
    ): String =
        c.agentRuntime
            .submit(
                SubmitTurnCommand(
                    SessionId(session),
                    ProviderId(provider),
                    AgentMode.ACT,
                    prompt,
                    budgets = TurnBudgets(maxOutputTokens = 8192),
                    reasoning = ReasoningEffort.OFF,
                    clientRequestId = UUID.randomUUID().toString(),
                    directUserRequest = true,
                ),
            ).value

    private suspend fun await(turn: String) =
        withTimeout(300_000) {
            while (c.storage.turns
                    .resolve(turn)
                    .state !in terminal
            ) {
                delay(200)
            }
            delay(200)
        }

    private fun snapshot(turn: String): JSONObject =
        JSONObject()
            .put("turn", turn)
            .put(
                "state",
                c.storage.turns
                    .resolve(turn)
                    .state,
            ).put(
                "error",
                c.storage.turns
                    .resolve(turn)
                    .errorCode,
            ).put(
                "calls",
                JSONArray(
                    c.storage.toolCalls.listByTurn(turn).map { call ->
                        JSONObject()
                            .put("name", call.name)
                            .put("state", call.state)
                            .put("args", call.argsJson)
                            .put(
                                "result",
                                c.storage.toolResults
                                    .byToolCall(call.id)
                                    ?.let(c.storage.toolResults::readContent),
                            )
                    },
                ),
            ).put(
                "modelCalls",
                JSONArray(
                    c.storage.modelCalls.listByTurn(turn).map { call ->
                        JSONObject()
                            .put("id", call.id)
                            .put("state", call.state)
                            .put("usage", call.usage)
                            .put("requestManifest", call.requestManifest)
                            .put("promptSections", call.promptSections)
                    },
                ),
            )
}
