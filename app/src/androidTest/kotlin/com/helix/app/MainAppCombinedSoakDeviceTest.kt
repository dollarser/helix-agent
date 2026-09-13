package com.helix.app

import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.provider.InAppMcpServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * EV-04 (U4) main-app combined soak. ONE method drives the real production stack — real
 * `MainActivity` (which binds the browser WebView host), `HelixApplication.appContainer`,
 * `ChatService` turn loop, and `ToolDispatcher` — through a fixed rotating grid of task classes
 * (master plan:58 "每10分钟块包含五类任务各一次…固定本地协议服务提供模型输出，标明不是模型能力评测").
 *
 * It is deliberately TEST-ONLY: no product code is touched, and the model output is DETERMINISTIC
 * (an in-APK [ScriptedTaskModelServer] on a real 127.0.0.1 socket, plus an in-APK [InAppMcpServer]
 * for the MCP class) — this proves the RUNTIME plumbing (turn loop / dispatcher / approvals /
 * goal settlement / browser host / MCP egress) over a long window, NOT model capability.
 *
 * Contract (mirrored line-for-line by scripts/test-run-ev04-mainapp-soak-fixture-logic.py):
 *  - `blockTaskGrid(block, flavor)` covers each expected class exactly once per block; the four
 *    base non-goal classes rotate by block; developer adds `proot`+`cli`; `goal` is ALWAYS last.
 *  - Goal parity: odd blocks COMPLETED (model goal.report), even blocks PAUSED (explicit user pause) -> 6/6 over 12.
 *  - Writes `progress.json` (runId/phase/seq/block/updatedMonotonicMs), `cycles.jsonl`,
 *    `heartbeat.jsonl`, and the terminal `soak-done.json` (runId/flavor/blocks/goalSuccess/
 *    goalStop/taskClasses/singlePid/pid/durationMs int/resourceStart/resourceEnd) to the app
 *    process external files dir (the instrumentation runs in the APP process, not the test uid).
 *  - Logs `HelixSoak:pid=<pid>` (the runner's single-pid invariant greps this).
 *
 * HONESTY: every task records "ok" ONLY when its real operation verified. A task whose real
 * precondition is unmet (e.g. the developer PRoot companion not installed) records "fail" with a
 * precise note rather than faking "ok". The developer `cli` class drives a real CLI-protocol
 * conversation through the subscription provider (master plan:66 CLI fixture conversation, no real
 * token) with model pinned to `helix-fixture`, so the runtime answers deterministically (HELIX_OK)
 * and never sends account traffic. The JUnit method completes without throwing so `soak-done.json`
 * is always written; the host runner judges PASS/FAIL from it.
 */
@RunWith(AndroidJUnit4::class)
class MainAppCombinedSoakDeviceTest {
    /**
     * `ActivityScenarioRule(MainActivity)` keeps the real entry activity alive for the whole soak,
     * which is what binds the browser WebView host (see FixedBrowserEvaluationDeviceTest). It is
     * required for the `browser` class (navigate/snapshot need a live host); the programmatic drive
     * does not click the UI, so the first-launch notice is irrelevant.
     */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var app: HelixApplication
    private val container get() = app.appContainer
    private val chat get() = container.chatService
    private val browser get() = container.browser

    // -- instrumentation args (runner -e helix.soak.*) with sane standalone/pilot defaults -------
    private var runId = "ev04"
    private var preset = "round2h"
    private var flavor = "consumer"
    private var blocks = 12
    private var blockSeconds = 600
    private var taskMaxSeconds = 90
    private var heartbeatSeconds = 30

    // -- run state --------------------------------------------------------------------------------
    private var pid = 0
    private var seq = 0
    private var startedMonotonic = 0L
    private var resourceStart: Sample? = null
    private var goalSuccess = 0
    private var goalStop = 0
    private val classStatus = LinkedHashMap<String, String>()

    private var modelServer: ScriptedTaskModelServer? = null
    private var mcpServer: InAppMcpServer? = null
    private var providerId: String? = null
    private var mcpToolName: String? = null
    private var mcpSetupFailed = ""

    @Test
    fun mainAppCombinedSoak() {
        app = ApplicationProvider.getApplicationContext()
        readArgs()
        pid = android.os.Process.myPid()
        val filesDir = requireNotNull(app.getExternalFilesDir(null))
        startedMonotonic = SystemClock.elapsedRealtime()
        resourceStart = sample()
        // Ensure the runner's pulled files start clean for this runId.
        for (name in FILES) File(filesDir, name).delete()

        Log.i(TAG, "HelixSoak:pid=$pid runId=$runId preset=$preset flavor=$flavor blocks=$blocks")
        writeProgress(filesDir, "running", 0)

        val previous = chat.runControl.value
        var runThrew: String? = null
        try {
            setupInfrastructure(filesDir)
            warmBrowserHost()
            for (block in 1..blocks) {
                writeProgress(filesDir, "running", block)
                for (cls in blockTaskGrid(block, flavor)) {
                    val (status, note) = runTask(cls, block, filesDir)
                    recordClass(cls, status)
                    appendHeartbeat(filesDir, block, cls, status)
                    writeProgress(filesDir, "running", block)
                }
            }
            if (flavor == "consumer") {
                consumerChannelAbsentCheck()
            }
        } catch (e: Exception) {
            // A setup/loop crash still yields a soak-done so the runner can classify it.
            runThrew = (e.message ?: e::class.simpleName ?: "crash").take(300)
            for (c in expectedClasses(flavor)) recordClass(c, "fail")
            Log.e(TAG, "HelixSoak:pid=$pid run error: $runThrew", e)
        } finally {
            try {
                chat.stop()
                chat.setMode(previous.mode)
                chat.setTurnBudgets(previous.budgets)
            } catch (e: Exception) {
                Log.w(TAG, "HelixSoak:pid=$pid teardown reset failed: $e")
            }
            val durationMs = (SystemClock.elapsedRealtime() - startedMonotonic).toInt()
            val end = sample()
            writeSoakDone(filesDir, durationMs, end, runThrew)
            writeProgress(filesDir, "done", blocks)
            tearDownInfrastructure()
        }
        Log.i(TAG, "HelixSoak:pid=$pid finished")
    }

    // ===========================================================================================
    //  Pure scheduling/accounting logic — MUST mirror the Python reference line-for-line.
    // ===========================================================================================

    private fun expectedClasses(f: String): List<String> =
        BASE_CLASSES + if (f == "developer") DEVELOPER_EXTRA else emptyList()

    /** 1-based block grid: base (minus goal) rotated by (block-1) % 4, + developer extras, goal last. */
    private fun blockTaskGrid(
        block: Int,
        f: String,
    ): List<String> {
        val base = BASE_CLASSES.filter { it != "goal" } // [chat, files, browser, mcp]
        val dev = if (f == "developer") DEVELOPER_EXTRA else emptyList()
        val rot = (block - 1) % base.size
        val rotated = base.subList(rot, base.size) + base.subList(0, rot)
        return rotated + dev + listOf("goal")
    }

    /** Odd blocks succeed (COMPLETED); even blocks Stop (PAUSED). */
    private fun goalOutcome(block: Int): Boolean = (block % 2) == 1

    // ===========================================================================================
    //  Setup / teardown
    // ===========================================================================================

    private fun readArgs() {
        val b = InstrumentationRegistry.getArguments()
        runId = b.getString("helix.soak.runId") ?: runId
        preset = b.getString("helix.soak.preset") ?: preset
        flavor = b.getString("helix.soak.flavor") ?: flavor
        blocks = b.getString("helix.soak.blocks")?.toIntOrNull() ?: blocks
        blockSeconds = b.getString("helix.soak.blockSeconds")?.toIntOrNull() ?: blockSeconds
        taskMaxSeconds = b.getString("helix.soak.taskMaxSeconds")?.toIntOrNull() ?: taskMaxSeconds
        heartbeatSeconds = b.getString("helix.soak.heartbeatSeconds")?.toIntOrNull() ?: heartbeatSeconds
    }

    @Suppress("UnusedParameter") // filesDir kept for call-site symmetry with the other cycle-appenders
    private fun setupInfrastructure(filesDir: File) {
        val model = ScriptedTaskModelServer()
        model.start()
        modelServer = model
        providerId = runBlocking { createProvider(model.port) }
        Log.i(TAG, "HelixSoak:pid=$pid provider=$providerId modelPort=${model.port}")

        val mcp = InAppMcpServer()
        mcp.start()
        mcpServer = mcp
        try {
            runBlocking {
                container.mcpService.registerDisabled(
                    MCP_SERVER_ID,
                    "http://127.0.0.1:${mcp.port}/mcp",
                    null,
                )
                val snapshot = container.mcpService.testConnection(MCP_SERVER_ID)
                val toolName =
                    snapshot.metadata.tools
                        .first()
                        .name
                container.mcpService.enable(snapshot, setOf(toolName))
                mcpToolName = "mcp.$MCP_SERVER_ID.$toolName"
            }
            Log.i(TAG, "HelixSoak:pid=$pid mcp enabled tool=$mcpToolName")
        } catch (e: Exception) {
            // A failed MCP handshake means the mcp class records "fail" per block (honest).
            mcpSetupFailed = (e.message ?: e::class.simpleName ?: "mcp setup failed").take(200)
            Log.w(TAG, "HelixSoak:pid=$pid mcp setup failed: $mcpSetupFailed")
        }
    }

    private fun tearDownInfrastructure() {
        try {
            if (mcpToolName != null) container.mcpService.delete(MCP_SERVER_ID)
        } catch (e: Exception) {
            Log.w(TAG, "HelixSoak:pid=$pid mcp delete failed: $e")
        }
        providerId?.let { id -> runCatching { runBlocking { container.providerService.delete(id) } } }
        runCatching { modelServer?.close() }
        runCatching { mcpServer?.close() }
    }

    @Suppress("UseCheckOrError") // precondition violations surface as IllegalStateException for the soak abort path
    private suspend fun createProvider(port: Int): String {
        container.storage.providerConfigs
            .list()
            .filter {
                it.displayName == "EV04 soak fixture" &&
                    it.model == ScriptedTaskModelServer.MODEL_ID &&
                    it.endpoint.startsWith("http://127.0.0.1:")
            }.forEach { container.providerService.delete(it.id) }
        val id =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "EV04 soak fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    ScriptedTaskModelServer.MODEL_ID,
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        val ok = container.providerService.runConnectionTest(id) is ProbeOutcome.Ok
        if (!ok) {
            container.providerService.delete(id)
            throw IllegalStateException("EV04 fixture provider probe failed")
        }
        return id
    }

    /** Bounded warm-up so the first browser task finds a bound host (MainActivity already up). */
    @Suppress("SwallowedException") // host may not be ready yet; the bounded poll retries until the deadline
    private fun warmBrowserHost() {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val tabId = main { browser.newTab() }
                val appeared = (0 until 100).any { browser.tab(tabId) != null }
                runCatching { main { browser.closeTab(tabId) } }
                if (appeared) return
            } catch (e: Exception) {
                // host not ready yet
            }
            Thread.sleep(300)
        }
        Log.w(TAG, "HelixSoak:pid=$pid browser host warm-up did not confirm; browser class will record honestly")
    }

    // ===========================================================================================
    //  Per-task runner
    // ===========================================================================================

    private fun runTask(
        cls: String,
        block: Int,
        filesDir: File,
    ): Pair<String, String> {
        val resStart = sample()
        val startedAt = SystemClock.elapsedRealtime()
        var status = "fail"
        var note = "uninitialized"
        var session: String? = null
        try {
            if (cls == "cli") {
                // The cli class drives its OWN subscription-provider session (a different model
                // backend than the ScriptedTaskModelServer the base classes use); it owns the
                // provider/session lifecycle itself (master plan:66 "CLI协议fixture对话（无真实token）").
                status = runBlocking { driveCli(block) }
                note = "ok"
            } else {
                session =
                    runBlocking {
                        container.chatService.createSession(
                            "EV04-$cls-$block",
                            requireNotNull(providerId),
                            ScriptedTaskModelServer.MODEL_ID,
                        )
                    }
                chat.openSession(session)
                chat.setMode(if (cls == "goal") AgentMode.GOAL else AgentMode.CHAT)
                chat.setTurnBudgets(TurnBudgets(8, 6, 131072, 4096, 131072))
                status = runBlocking { driveTask(cls, block, session) }
                note = "ok"
            }
        } catch (e: Exception) {
            note = (e.message ?: e::class.simpleName ?: "error").take(300)
        } finally {
            try {
                chat.stop()
                chat.closeSession()
            } catch (e: Exception) {
                Log.w(TAG, "HelixSoak:pid=$pid $cls session teardown: $e")
            }
            session?.let { s -> runCatching { container.storage.sessions.archive(s, System.currentTimeMillis()) } }
            val dur = SystemClock.elapsedRealtime() - startedAt
            recordClass(cls, status)
            appendCycle(filesDir, block, cls, status, note, startedAt, dur, resStart)
            Log.i(TAG, "HelixSoak:pid=$pid block=$block class=$cls status=$status note=$note")
        }
        return status to note
    }

    private fun recordClass(
        cls: String,
        status: String,
    ) {
        classStatus[cls] = if (classStatus[cls] == "ok") status else status
    }

    @Suppress("UseCheckOrError") // precondition violations surface as IllegalStateException for the soak abort path
    private suspend fun driveTask(
        cls: String,
        block: Int,
        session: String,
    ): String =
        when (cls) {
            "chat" -> driveChat(block, session)
            "files" -> driveFiles(block, session)
            "browser" -> driveBrowser(block, session)
            "mcp" -> driveMcp(block, session)
            "goal" -> driveGoal(block, session)
            "proot" -> driveProot(block, session)
            "cli" -> driveCli(block)
            else -> throw IllegalStateException("unknown task class: $cls")
        }

    // -- chat: a plain text turn ends COMPLETED with the scripted final text ---------------------
    private fun driveChat(
        block: Int,
        session: String,
    ): String {
        val finalText = "EV04-CHAT-$block"
        requireNotNull(modelServer).arm(emptyList(), finalText)
        chat.send("EV04 chat probe $block")
        val turn = requireTerminal(awaitTurn(session, block))
        val sawFinal =
            container.storage.messages.listBySession(session).any {
                container.storage.messages.readContent(it) == finalText
            }
        require(sawFinal) { "chat: assistant final text not recorded (turn=${turn.state})" }
        return "ok"
    }

    // -- files: real write (approved) then read, content round-trips -----------------------------
    private fun driveFiles(
        block: Int,
        session: String,
    ): String {
        val path = "scope:app:output/ev04-$block-files.txt"
        val content = "EV04 file content $block"
        requireNotNull(modelServer).arm(
            listOf(
                ScriptedTaskModelServer.Step("write") {
                    JSONObject().put("path", path).put("content", content).toString()
                },
                ScriptedTaskModelServer.Step("read") { JSONObject().put("path", path).toString() },
            ),
            "EV04 files done $block",
        )
        chat.send("Write then read the file.")
        val turn = requireTerminal(awaitTurn(session, block))
        val calls = container.storage.toolCalls.listByTurn(turn.id)
        val writeCall = requireNotNull(calls.firstOrNull { it.name == "write" }) { "files: no write call" }
        val readCall = requireNotNull(calls.firstOrNull { it.name == "read" }) { "files: no read call" }
        require(ToolCallState.valueOf(writeCall.state) == ToolCallState.COMPLETED) {
            "files: write not COMPLETED (state=${writeCall.state})"
        }
        require(ToolCallState.valueOf(readCall.state) == ToolCallState.COMPLETED) {
            "files: read not COMPLETED (state=${readCall.state})"
        }
        val writeApproval = container.storage.approvals.byToolCall(writeCall.callId)
        require(writeApproval != null && writeApproval.decision == "APPROVED") {
            "files: write approval not APPROVED"
        }
        val readContent =
            container.storage.toolResults.byToolCall(readCall.callId)?.let {
                container.storage.toolResults.readContent(it)
            }
        require(readContent != null && readContent.contains(content)) {
            "files: read-back content mismatch"
        }
        return "ok"
    }

    // -- browser: host navigate (test action) + real snapshot/click (model), then close ----------
    private fun driveBrowser(
        block: Int,
        session: String,
    ): String {
        val tabId = main { browser.newTab() }
        try {
            val html =
                "<html><body><h1>EV04 Browser $block</h1>" +
                    "<a href=\"#clicked-$block\">EV04 Link $block</a></body></html>"
            main { browser.navigate(tabId, "data:text/html," + Uri.encode(html)) }
            require(awaitBrowserLoaded(tabId)) { "browser: page did not settle" }
            requireNotNull(modelServer).arm(
                listOf(
                    ScriptedTaskModelServer.Step("browser.snapshot") {
                        JSONObject().put("tabId", tabId).toString()
                    },
                    ScriptedTaskModelServer.Step("browser.click") {
                        val snap = main { browser.latestSnapshot(tabId) }
                        val token =
                            snap
                                ?.nodes
                                ?.firstOrNull {
                                    it.role == "link" || it.role == "button"
                                }?.token ?: ""
                        JSONObject().put("tabId", tabId).put("token", token).toString()
                    },
                ),
                "EV04 browser done $block",
            )
            chat.send("Snapshot the page then click the approved link.")
            val turn = requireTerminal(awaitTurn(session, block))
            val calls = container.storage.toolCalls.listByTurn(turn.id)
            val snapCall =
                requireNotNull(calls.firstOrNull { it.name == "browser.snapshot" }) {
                    "browser: no snapshot call"
                }
            val clickCall =
                requireNotNull(calls.firstOrNull { it.name == "browser.click" }) {
                    "browser: no click call"
                }
            require(ToolCallState.valueOf(snapCall.state) == ToolCallState.COMPLETED) {
                "browser: snapshot not COMPLETED (state=${snapCall.state})"
            }
            require(ToolCallState.valueOf(clickCall.state) == ToolCallState.COMPLETED) {
                "browser: click not COMPLETED (state=${clickCall.state})"
            }
        } finally {
            runCatching { main { browser.closeTab(tabId) } }
        }
        return "ok"
    }

    @Suppress("ComplexCondition") // settled page = present + not loading + no error + navigated
    private fun awaitBrowserLoaded(tabId: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + (taskMaxSeconds * 600L)
        while (SystemClock.elapsedRealtime() < deadline) {
            val tab = browser.tab(tabId)
            if (tab != null && !tab.isLoading && tab.error == null && tab.navigationGeneration > 0) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    // -- mcp: real handshake + egress against the in-APK server; result carries SYNTHETIC_READ_OK -
    private fun driveMcp(
        block: Int,
        session: String,
    ): String {
        val toolName =
            requireNotNull(mcpToolName) { "mcp: not enabled at setup ($mcpSetupFailed)" }
        requireNotNull(modelServer).arm(
            listOf(
                ScriptedTaskModelServer.Step(toolName) { JSONObject().put("case", "ev04").toString() },
            ),
            "EV04 mcp done $block",
        )
        chat.send("Read the fixture through MCP.")
        val turn = requireTerminal(awaitTurn(session, block))
        val mcpCall =
            requireNotNull(
                container.storage.toolCalls
                    .listByTurn(turn.id)
                    .firstOrNull { it.name == toolName },
            ) { "mcp: no $toolName call" }
        require(ToolCallState.valueOf(mcpCall.state) == ToolCallState.COMPLETED) {
            "mcp: call not COMPLETED (state=${mcpCall.state})"
        }
        val result =
            container.storage.toolResults.byToolCall(mcpCall.callId)?.let {
                container.storage.toolResults.readContent(it)
            }
        require(result != null && result.contains(InAppMcpServer.READ_TEXT)) {
            "mcp: result did not carry SYNTHETIC_READ_OK"
        }
        return "ok"
    }

    // -- goal: odd blocks COMPLETED via goal.report; even blocks Stop -> PAUSED (6/6 over 12) ---
    @Suppress("LongMethod", "UseCheckOrError") // goal drive is one fixture path; throws mark the soak abort
    private suspend fun driveGoal(
        block: Int,
        session: String,
    ): String {
        val success = goalOutcome(block)
        val goalId =
            chat.createGoal(
                "EV04 goal block $block",
                listOf("EV04 criterion $block"),
                GoalBudgets(6, 4, 131072, 300000, 300000, 0),
            )
        try {
            if (success) {
                val path = "scope:app:output/ev04-$block-goal.txt"
                requireNotNull(modelServer).arm(
                    listOf(
                        ScriptedTaskModelServer.Step("write") {
                            JSONObject().put("path", path).put("content", "EV04 goal $block").toString()
                        },
                        ScriptedTaskModelServer.Step("read") { JSONObject().put("path", path).toString() },
                        ScriptedTaskModelServer.Step("goal.report") {
                            JSONObject()
                                .put("status", "complete")
                                .put("summary", "EV04 goal $block: synthetic write/read completed")
                                .toString()
                        },
                    ),
                    "EV04 goal done $block",
                )
            } else {
                requireNotNull(modelServer).arm(
                    emptyList(),
                    "EV04 goal held for user pause $block",
                    holdFinalResponse = true,
                )
            }
            chat.continueGoal(goalId, "Objective: EV04 goal block $block")
            if (!success) {
                // A real pending model request is the barrier: natural Turn completion is not Stop.
                val server = requireNotNull(modelServer)
                check(server.finalResponseRequested.await(20, TimeUnit.SECONDS)) {
                    "EV04 pause never reached a pending model request"
                }
                check(
                    container.storage.goals
                        .resolve(goalId)
                        .state == "RUNNING",
                )
                val activeTurn =
                    requireNotNull(
                        container.storage.turns
                            .listBySession(session)
                            .lastOrNull(),
                    )
                chat.stopTask(activeTurn.id, pause = true)
            }
            val settled = awaitTurn(session, block)
            if (success) {
                requireTerminal(settled)
                check(settled?.state == "COMPLETED") { "report Turn did not complete" }
                check(
                    container.storage.toolCalls.listByTurn(requireNotNull(settled).id).any {
                        it.name == "goal.report" && it.state == "COMPLETED"
                    },
                ) { "no settled goal.report ToolCall" }
            } else {
                check(settled?.state == "CANCELLED") { "user Stop did not cancel the Turn: ${settled?.state}" }
                check(settled?.pauseRequestedAt != null) { "missing durable user pause request" }
                requireNotNull(modelServer).releaseFinalResponse()
            }
            val deadline = SystemClock.elapsedRealtime() + 10000
            var state =
                container.storage.goals
                    .resolve(goalId)
                    .state
            while (
                state !in setOf("COMPLETED", "PAUSED", "BLOCKED", "FAILED", "CANCELLED") &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(100)
                state =
                    container.storage.goals
                        .resolve(goalId)
                        .state
            }
            if (success) {
                if (state == "COMPLETED") {
                    goalSuccess++
                } else {
                    throw IllegalStateException("odd goal expected COMPLETED, got $state")
                }
            } else {
                if (state == "PAUSED") {
                    goalStop++
                } else {
                    throw IllegalStateException("even goal expected PAUSED, got $state")
                }
            }
            return "ok"
        } finally {
            requireNotNull(modelServer).releaseFinalResponse()
            runCatching {
                val stored = container.storage.goals.find(goalId)
                if (stored != null && stored.state != "RUNNING") {
                    container.privacyDeletionService.deleteGoal(goalId)
                }
            }
        }
    }

    // -- developer proot: real approved code.linux.run guest write + read-back (companion-gated) --
    private fun driveProot(
        block: Int,
        session: String,
    ): String {
        val present =
            container.toolPipeline.registry.resolveLatest(ToolName("code.linux.run")) != null
        require(present) { "proot: code.linux.run not registered (developer channel absent)" }
        val outPath = "scope:app:output/ev04-$block-proot.txt"
        requireNotNull(modelServer).arm(
            listOf(
                ScriptedTaskModelServer.Step("code.linux.run") {
                    JSONObject()
                        .put(
                            "argv",
                            org.json
                                .JSONArray()
                                .put("/bin/sh")
                                .put("-c")
                                .put("echo EV04_PROOT_$block > result.txt"),
                        ).put("output", outPath)
                        .toString()
                },
                ScriptedTaskModelServer.Step("read") { JSONObject().put("path", outPath).toString() },
            ),
            "EV04 proot done $block",
        )
        chat.send("Run the PRoot job and read back its output.")
        val turn = requireTerminal(awaitTurn(session, block))
        val calls = container.storage.toolCalls.listByTurn(turn.id)
        val runCall =
            requireNotNull(calls.firstOrNull { it.name == "code.linux.run" }) {
                "proot: no code.linux.run call"
            }
        // Honest gate: without the PRoot companion the real guest write cannot succeed.
        require(ToolCallState.valueOf(runCall.state) == ToolCallState.COMPLETED) {
            "proot: code.linux.run not COMPLETED (state=${runCall.state}); " +
                "PRoot companion install + anchor required before this round (device pilot)"
        }
        val readCall = requireNotNull(calls.firstOrNull { it.name == "read" }) { "proot: no read call" }
        val readContent =
            container.storage.toolResults.byToolCall(readCall.callId)?.let {
                container.storage.toolResults.readContent(it)
            }
        require(readContent != null && readContent.contains("EV04_PROOT_$block")) {
            "proot: guest read-back mismatch"
        }
        return "ok"
    }

    // -- developer cli: real CLI-protocol fixture conversation (master plan:66 "无真实token") ----
    // The subscription provider (e.g. subscription-codex) routes each turn through CliRuntimeService
    // (separate APK, Binder IPC). With the model pinned to `helix-fixture` the runtime answers
    // deterministically (HELIX_OK) in a DEBUG build and NEVER sends account traffic — so this is a
    // deterministic, test-only exercise of the real CLI job path, not a real-subscription test.
    private suspend fun driveCli(block: Int): String {
        val cliProviderId = CODEX_ID
        val original =
            requireNotNull(container.storage.providerConfigs.resolve(cliProviderId)) {
                "cli: subscription provider $cliProviderId not present (developer channel absent)"
            }
        val prevTools = chat.runControl.value.chatToolsEnabled
        val session = chat.createSession("EV04-cli-$block", cliProviderId, CLI_FIXTURE_MODEL)
        try {
            chat.setChatToolsEnabled(false) // the CLI provider carries no tool calls
            container.storage.providerConfigs.overwrite(spec(original, CLI_FIXTURE_MODEL))
            require(container.providerService.runConnectionTest(cliProviderId) is ProbeOutcome.Ok) {
                "cli: provider probe failed for $cliProviderId"
            }
            chat.openSession(session)
            chat.send("EV04 cli fixture conversation $block")
            val turn = requireTerminal(awaitTurn(session, block))
            require(
                container.storage.messages.listBySession(session).any {
                    container.storage.messages.readContent(it) == "HELIX_OK"
                },
            ) { "cli: HELIX_OK not recorded (turn=${turn.state})" }
            return "ok"
        } finally {
            runCatching {
                container.storage.providerConfigs.overwrite(spec(original, original.model))
                container.providerService.refresh()
            }
            runCatching { chat.setChatToolsEnabled(prevTools) }
            runCatching { chat.closeSession() }
            runCatching { container.storage.sessions.archive(session, System.currentTimeMillis()) }
        }
    }

    private fun spec(
        entity: com.helix.core.storage.entity.ProviderConfigEntity,
        model: String,
    ) = ProviderConfigSpec(
        id = entity.id,
        displayName = entity.displayName,
        protocol = ProviderProtocol.parse(entity.protocol),
        endpoint = entity.endpoint,
        model = model,
        headersJson = entity.headersJson,
        secretAlias = entity.secretAlias,
        capabilitySnapshot = entity.capabilitySnapshot,
    )

    // -- consumer: the proot channel MUST be absent (a consumer build has no LinuxRunTool) -------
    private fun consumerChannelAbsentCheck() {
        val prootAbsent =
            container.toolPipeline.registry.resolveLatest(ToolName("code.linux.run")) == null
        if (!prootAbsent) {
            // A consumer build leaking the developer PRoot tool is a build-config error: invalidate
            // the whole round (the runner's consumer check_classes then fails all five base classes).
            for (c in expectedClasses(flavor)) recordClass(c, "fail")
            Log.e(TAG, "HelixSoak:pid=$pid consumer invariant violated: code.linux.run present")
        }
        // The CLI channel is a subscription *provider* (not a tool); consumer builds have no
        // subscription providers, so the `cli` class never enters a consumer grid — no separate
        // consumer assertion is needed here.
    }

    // ===========================================================================================
    //  Turn / approval drive
    // ===========================================================================================

    private fun requireTerminal(
        turn: com.helix.core.storage.entity.TurnEntity?,
    ): com.helix.core.storage.entity.TurnEntity {
        requireNotNull(turn) { "turn did not start" }
        val ts = TurnState.valueOf(turn.state)
        require(ts.isTerminal && ts == TurnState.COMPLETED) {
            "turn not COMPLETED (state=${turn.state})"
        }
        return turn
    }

    private fun awaitTurn(
        session: String,
        block: Int,
    ): com.helix.core.storage.entity.TurnEntity? {
        val deadline = SystemClock.elapsedRealtime() + (taskMaxSeconds * 1000L)
        var lastProgress = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            resolveApprovals(session)
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null && TurnState.valueOf(turn.state).isTerminal) return turn
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgress > 10000) {
                // keep the runner's progress.json watchdog alive across a long task
                seq++
                writeProgress(requireNotNull(app.getExternalFilesDir(null)), "running", block)
                lastProgress = now
            }
            Thread.sleep(150)
        }
        return container.storage.turns
            .listBySession(session)
            .lastOrNull()
    }

    @Suppress("SwallowedException", "NestedBlockDepth") // nested matrix; a failed apply retries each poll
    private fun resolveApprovals(session: String) {
        val turn =
            container.storage.turns
                .listBySession(session)
                .lastOrNull() ?: return
        for (call in container.storage.toolCalls.listByTurn(turn.id)) {
            if (call.state == "AWAITING_APPROVAL") {
                val approval = container.storage.approvals.byToolCall(call.callId)
                if (approval != null && approval.decision == null) {
                    try {
                        chat.approveApproval(approval.id)
                    } catch (e: Exception) {
                        // retried on the next poll
                    }
                }
            }
        }
    }

    // ===========================================================================================
    //  Resource sampling + file contract writers
    // ===========================================================================================

    private data class Sample(
        val fd: Int,
        val threads: Int,
        val pssKb: Long,
    )

    @Suppress("SwallowedException") // a /proc read failure records -1 (attribution, not gating)
    private fun sample(): Sample {
        val fd =
            runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)
        val threads =
            runCatching { File("/proc/self/task").list()?.size ?: -1 }.getOrDefault(-1)
        val pssKb =
            runCatching { Debug.getPss() }.getOrDefault(-1L)
        return Sample(fd, threads, pssKb)
    }

    private fun writeProgress(
        filesDir: File,
        phase: String,
        block: Int,
    ) {
        seq++
        val obj =
            JSONObject()
                .put("runId", runId)
                .put("phase", phase)
                .put("seq", seq)
                .put("block", block)
                .put("updatedMonotonicMs", SystemClock.elapsedRealtime())
        File(filesDir, "progress.json").writeText(obj.toString())
    }

    @Suppress("LongParameterList") // one column per cycle-record field
    private fun appendCycle(
        filesDir: File,
        block: Int,
        cls: String,
        status: String,
        note: String,
        startedAt: Long,
        durationMs: Long,
        resStart: Sample,
    ) {
        val obj =
            JSONObject()
                .put("seq", seq)
                .put("block", block)
                .put("class", cls)
                .put("pid", pid)
                .put("startedMonotonicMs", startedAt)
                .put("endedMonotonicMs", startedAt + durationMs)
                .put("durationMs", durationMs)
                .put("fdStart", resStart.fd)
                .put("threadsStart", resStart.threads)
                .put("pssKbStart", resStart.pssKb)
                .put("status", status)
                .put("note", note)
        File(filesDir, "cycles.jsonl").appendText(obj.toString() + "\n")
    }

    private fun appendHeartbeat(
        filesDir: File,
        block: Int,
        cls: String,
        status: String,
    ) {
        val obj =
            JSONObject()
                .put("seq", seq)
                .put("block", block)
                .put("class", cls)
                .put("status", status)
                .put("pid", pid)
                .put("monMs", SystemClock.elapsedRealtime())
        File(filesDir, "heartbeat.jsonl").appendText(obj.toString() + "\n")
    }

    private fun writeSoakDone(
        filesDir: File,
        durationMs: Int,
        end: Sample,
        runThrew: String?,
    ) {
        val start = requireNotNull(resourceStart)
        // taskClasses: every expected class "ok" unless a task of it recorded "fail".
        val taskClasses = JSONObject()
        for (c in expectedClasses(flavor)) {
            taskClasses.put(c, classStatus[c] ?: "fail")
        }
        val obj =
            JSONObject()
                .put("runId", runId)
                .put("flavor", flavor)
                .put("blocks", blocks)
                .put("goalMechanism", "model-report-user-pause-v1")
                .put("goalSuccess", goalSuccess)
                .put("goalStop", goalStop)
                .put("taskClasses", taskClasses)
                .put("singlePid", true)
                .put("pid", pid)
                .put("durationMs", durationMs)
                .put(
                    "resourceStart",
                    JSONObject()
                        .put("fd", start.fd)
                        .put("threads", start.threads)
                        .put("pssKb", start.pssKb),
                ).put(
                    "resourceEnd",
                    JSONObject()
                        .put("fd", end.fd)
                        .put("threads", end.threads)
                        .put("pssKb", end.pssKb),
                )
        if (runThrew != null) obj.put("runError", runThrew)
        File(filesDir, "soak-done.json").writeText(obj.toString())
        Log.i(TAG, "HelixSoak:pid=$pid soak-done $obj")
    }

    /** Runs [action] on the Android main looper (browser controller is main-thread-bound). */
    private fun <T> main(action: () -> T): T {
        val future = FutureTask<T>(Callable { action() })
        Handler(Looper.getMainLooper()).post(future)
        return future.get(30, TimeUnit.SECONDS)
    }

    private companion object {
        const val TAG: String = "HelixSoak"
        const val MCP_SERVER_ID: String = "ev04mcp"
        const val CLI_FIXTURE_MODEL: String = "helix-fixture"

        // The app-module CODEX_ID is developer-only; restated here so this shared androidTest source
        // set still compiles on the consumer flavor (driveCli is invoked only on the developer round).
        const val CODEX_ID: String = "subscription-codex"
        val BASE_CLASSES: List<String> = listOf("chat", "files", "browser", "mcp", "goal")
        val DEVELOPER_EXTRA: List<String> = listOf("proot", "cli")
        val FILES: List<String> =
            listOf("progress.json", "cycles.jsonl", "heartbeat.jsonl", "soak-done.json")
    }
}
