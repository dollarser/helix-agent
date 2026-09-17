package com.helix.app

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.ArtifactQuery
import com.helix.app.chat.ChatService
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ModelEvent
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.RiskLevel
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.entity.GoalControlEntity
import com.helix.core.storage.mapping.StoredGoal
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-202 slice 4 — the full task journey on a real device: cross-session navigation by
 * stable ID, a goal entry that selects its owning session without continuing, a stop that
 * stays visible as cancelling until the durable settlement, artifacts listed by real task
 * ownership, and a REAL two-phase process recovery proving restoration never re-executes.
 *
 * Phase protocol (same shape as SessionPermissionRecoveryDeviceTest, D8-accepted):
 * the matrix runs this class twice against the SAME installation — `recoveryPhase=setup`
 * seeds the journey history (fixed IDs, idempotent), records the process ID and kills the
 * process; `recoveryPhase=verify` asserts the new PID opened the same database with the
 * history, artifact ownership and goal states intact and NO new turn or goal run. With no
 * phase argument everything runs in one process (standalone). The recovery method runs in
 * every run; the journey methods `assumeTrue`-skip in the setup run, so each method
 * genuinely executes in exactly one quadrant pass and a skip is never a pass.
 *
 * The owned AVD is portrait-locked at 1080x2400 and the test sources have no UiDevice
 * dependency, so "rotation" is covered by the activity rebuild path a configuration change
 * goes through (`recreate` twice) plus a real background/foreground cycle — all of which
 * must restore the dashboard from durable facts without starting anything.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // one method per journey facet plus the shared fixture helpers
class TaskJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepareStandaloneJourney() {
        // Ordinary JUnit order is unspecified. Only a normal run seeds its fixture;
        // restart verification must observe the existing persisted rows without repair.
        if (recoveryPhase() == null) {
            seedJourneyHistory(ApplicationProvider.getApplicationContext(), containerFromApp())
        }
    }

    /**
     * Seed the journey history with FIXED ids (no per-run suffix so both phases address the
     * same rows), then either kill the process (setup) or assert the seeded facts — in the
     * verify phase additionally through a different PID than the one that wrote them.
     */
    @Test
    fun journeyHistorySurvivesARestartWithoutReexecution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phase = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)
        val container = containerFromApp()
        if (phase == "verify") {
            val markerPid =
                context.noBackupFilesDir
                    .resolve(PID_MARKER)
                    .readText()
                    .toInt()
            assertNotEquals("recovery must run in a new process", markerPid, Process.myPid())
            assertSeededJourney(container.storage)
            assertEquals(
                "an unactivated goal must never auto-run",
                0,
                container.storage.goalRuns
                    .listByGoal(GOAL_DRAFT)
                    .size,
            )
            assertEquals(
                "the bound goal keeps exactly its seeded run",
                1,
                container.storage.goalRuns
                    .listByGoal(GOAL_BOUND)
                    .size,
            )
            return
        }
        seedJourneyHistory(context, container)
        writeRecoveryMarker(context)
        if (phase == "setup") {
            Process.killProcess(Process.myPid())
            error("the recovery setup kill must end this process")
        }
        assertSeededJourney(container.storage)
    }

    /** Two same-titled sessions stay separate rows and each row opens ITS own chat page. */
    @Test
    fun crossSessionTasksOpenTheirOwnChatPages() {
        assumeTrue(recoveryPhase() != "setup")
        val container = containerFromApp()
        val chat = container.chatService
        val storage = container.storage
        compose.resetDeterministicUiState()
        assertEquals(
            "the fixture rows are two sessions with one identical title",
            JOURNEY_TITLE,
            storage.sessions.resolve(SESSION_A).title,
        )
        assertEquals(JOURNEY_TITLE, storage.sessions.resolve(SESSION_B).title)
        compose.navigateTo("tasks")
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
        assertOpenOwnChatPage(TURN_A, SESSION_A, chat)
        assertOpenOwnChatPage(TURN_B, SESSION_B, chat)
    }

    /**
     * The Goal entry opens the goal's owning session on the chat page. Opening is an
     * observation: the UNACTIVATED fixture goal gains no run, no turn and no state change.
     */
    @Test
    fun goalEntrySwitchesToTheOwningChatPageWithoutContinuing() {
        assumeTrue(recoveryPhase() != "setup")
        val container = containerFromApp()
        val chat = container.chatService
        val storage = container.storage
        compose.resetDeterministicUiState()
        val before = journeyCounts(storage)
        assertEquals("the fixture goal must start unactivated", 0, before.draftRuns)
        compose.navigateTo("tasks")
        scrollToTask("tasks-goal-$GOAL_DRAFT")
        compose.onNodeWithTag("tasks-goal-open-$GOAL_DRAFT").performScrollTo().performClick()
        compose.waitForIdle()
        stopAwait { chat.screen.value.openSessionId == SESSION_A }
        compose.onNodeWithTag("screen-sessions").assertIsDisplayed()
        assertEquals("opening a goal must not start anything", before, journeyCounts(storage))
        assertEquals("DRAFT", storage.goals.resolve(GOAL_DRAFT).state)
        assertEquals(1, storage.turns.listBySession(SESSION_A).size)
    }

    /**
     * Opening, rebuilding (rotation equivalent, twice) and backgrounding/returning the
     * dashboard restore the same rows from durable facts and start nothing new — verified
     * against the unactivated fixture goal and the total turn count.
     */
    @Test
    fun openingRecreatingAndBackgroundingTheDashboardStartsNothing() {
        assumeTrue(recoveryPhase() != "setup")
        val storage = containerFromApp().storage
        compose.resetDeterministicUiState()
        val before = journeyCounts(storage)
        compose.navigateTo("tasks")
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
        rebuildDashboardAndWaitForRows()
        rebuildDashboardAndWaitForRows()
        backgroundAndReturnToDashboard()
        compose.navigateTo("tasks")
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
        assertEquals("rebuild and background return must start nothing", before, journeyCounts(storage))
    }

    /**
     * The production stop path against a stable ID, run as two fully settled journeys:
     * CANCELLING is persisted (and seen by a tight poller) before the settlement commits
     * CANCELLED, a repeated stop is a no-op, a stale stop on a completed turn changes
     * nothing, and stopping one turn never rewrites another turn's settled row.
     *
     * The journeys run one at a time BY DESIGN of the app, not as a test convenience: the
     * ToolScheduler serializes exclusive (mutating) tool calls across turns (doc 11 section
     * 3.1 — a write is a full barrier), and a pending approval HOLDS its tool call's slot
     * until the call settles. A second turn's tool call therefore only reaches its approval
     * after the first turn's stop releases the slot; stopping the first turn while the
     * second is in flight settles that barrier — it is not a race between two pending
     * approvals, and the app must never run one mutation under another's un-decided one.
     */
    @Test
    fun stopPersistsCancellingUntilSettledAndIsRaceSafe() {
        assumeTrue(recoveryPhase() != "setup")
        val container = containerFromApp()
        val chat = container.chatService
        val storage = container.storage
        val executions = AtomicInteger()
        val suffix = UUID.randomUUID().toString().take(8)
        registerFixtureEcho(container, executions)
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider = runBlocking { createFixtureProvider(container, server.port, suffix) }
            runBlocking {
                val sessionE = chat.createSession("HXA202 stop journey E", provider, MODEL)
                val sessionF = chat.createSession("HXA202 stop journey F", provider, MODEL)
                val previous = chat.runControl.value
                try {
                    val settledE = stopLiveJourney(storage, chat, sessionE, "Echo probe E.")
                    // A stale stop on the already-completed seeded turn changes nothing.
                    chat.stopTask(TURN_A)
                    stopAwait { storage.turns.resolve(TURN_A).state == "COMPLETED" }
                    assertEquals("COMPLETED", storage.turns.resolve(TURN_A).state)
                    // The second journey settles while E's row is already durable: its stop
                    // must be turn-scoped and rewrite nothing that E's stop committed.
                    val settledF = stopLiveJourney(storage, chat, sessionF, "Echo probe F.")
                    assertEquals(
                        "stopping a later turn must not rewrite an earlier settled row",
                        settledE,
                        settledRow(storage, settledE.id),
                    )
                    assertEquals(
                        "a stale stop on the completed seeded turn must survive both stops",
                        "COMPLETED",
                        storage.turns.resolve(TURN_A).state,
                    )
                    // The shared dashboard feed reaches the durable settlement for both stops.
                    stopAwait {
                        chat.backgroundTasks.value.any { it.id == settledE.id && it.state == TurnState.CANCELLED } &&
                            chat.backgroundTasks.value.any { it.id == settledF.id && it.state == TurnState.CANCELLED }
                    }
                    assertEquals("the fixture tool never executed", 0, executions.get())
                } finally {
                    stopIfLive(storage, chat, sessionE)
                    stopIfLive(storage, chat, sessionF)
                    chat.stop()
                    stopAwait { !chat.screen.value.isSending }
                    chat.closeSession()
                    chat.setMode(previous.mode)
                    container.providerService.delete(provider)
                }
            }
        }
    }

    /** The task-artifact dialogs list by real ownership: a turn's own rows, a goal's bound-turn union. */
    @Test
    fun taskArtifactsDialogListsByRealOwnership() {
        assumeTrue(recoveryPhase() != "setup")
        compose.resetDeterministicUiState()
        compose.navigateTo("tasks")
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
        assertTurnArtifactOwnership(listOf(ART_A1, ART_A2), listOf(ART_B1, ART_C1))
        assertGoalArtifactOwnership(GOAL_BOUND, listOf(ART_C1), listOf(ART_A1, ART_B1))
        assertGoalArtifactOwnership(GOAL_DRAFT, emptyList(), listOf(ART_A1))
    }

    // ---------- fixture seeding (fixed IDs, idempotent) ----------

    private fun seedJourneyHistory(
        context: Context,
        container: AppContainer,
    ) {
        val storage = container.storage
        val now = SystemClock().now().toEpochMilli()
        if (storage.sessions.list().none { it.id == SESSION_A }) {
            storage.sessions.create(SESSION_A, JOURNEY_TITLE, null, null, now)
        }
        if (storage.sessions.list().none { it.id == SESSION_B }) {
            storage.sessions.create(SESSION_B, JOURNEY_TITLE, null, null, now)
        }
        if (storage.sessions.list().none { it.id == SESSION_C }) {
            storage.sessions.create(SESSION_C, "HXA202 journey goal session", null, null, now)
        }
        seedCompletedTurn(storage, SESSION_A, TURN_A)
        seedCompletedTurn(storage, SESSION_B, TURN_B)
        seedCompletedTurn(storage, SESSION_C, TURN_C)
        seedArtifact(
            context,
            storage,
            ArtifactSpec(ART_A1, SESSION_A, TURN_A, "journey/a1/report.txt", "HXA202 journey report A1"),
        )
        seedArtifact(
            context,
            storage,
            ArtifactSpec(ART_A2, SESSION_A, TURN_A, "journey/a1/note.txt", "HXA202 journey note A2"),
        )
        seedArtifact(
            context,
            storage,
            ArtifactSpec(ART_B1, SESSION_B, TURN_B, "journey/b1/summary.txt", "HXA202 journey summary B1"),
        )
        seedArtifact(
            context,
            storage,
            ArtifactSpec(ART_C1, SESSION_C, TURN_C, "journey/c1/result.txt", "HXA202 journey result C1"),
        )
        seedGoals(storage)
    }

    /** A completed plain turn through the production coordinator — no loopback needed. */
    private fun seedCompletedTurn(
        storage: HelixStorage,
        sessionId: String,
        turnId: String,
    ) {
        if (storage.turns.find(turnId) != null) return
        val coordinator =
            TurnCoordinator.start(
                storage,
                SystemClock(),
                { UUID.randomUUID().toString() },
                TurnStartSpec(sessionId, turnId, "$turnId-model", "journey-fixture", "HXA202 journey fixture"),
            )
        coordinator.beginModelStream().apply(ModelEvent.TextDelta("HXA202 journey fixture result"))
        coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
    }

    private fun seedArtifact(
        context: Context,
        storage: HelixStorage,
        spec: ArtifactSpec,
    ) {
        val dir = context.filesDir.resolve("hxa202-journey-artifacts")
        val file = dir.resolve(spec.relativePath.replace('/', File.separatorChar))
        file.parentFile?.mkdirs()
        file.writeText(spec.content)
        storage.artifacts.registerOrRefresh(
            spec.id,
            spec.sessionId,
            spec.relativePath,
            "text/plain",
            file.length(),
            sha256Hex(file),
            spec.turnId,
            file,
        )
    }

    private fun sha256Hex(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun seedGoals(storage: HelixStorage) {
        if (storage.goals.find(GOAL_DRAFT) == null) {
            storage.withTransaction {
                storage.goals.save(
                    StoredGoal(
                        id = GOAL_DRAFT,
                        objective = "HXA202 journey draft goal",
                        criteria = listOf(StoredCriterion("criterion-1", "Journey draft criterion", null)),
                        budgets = GoalBudgets(2, 4, 1000, 60_000, 10_000, 0),
                        state = "DRAFT",
                        planId = null,
                        planHash = null,
                        nextCheckpoint = null,
                        correlationId = "$GOAL_DRAFT-correlation",
                        runCount = 0,
                        modelCalls = 0,
                        toolCalls = 0,
                        totalTokens = 0,
                        runTimeMillis = 0,
                        currentWakeMillis = 0,
                        retries = 0,
                        lastWakeReason = null,
                        error = null,
                        finishReason = null,
                    ),
                )
                storage.goalControls.insert(GoalControlEntity(GOAL_DRAFT, SESSION_A, 0, null, null))
            }
        }
        if (storage.goals.find(GOAL_BOUND) == null) {
            val startedAt = SystemClock().now().toEpochMilli()
            storage.withTransaction {
                // Replays the real lifecycle: the binding admits only while the goal is RUNNING and
                // its run still open, and the completed goal rows persist only after the run closes.
                storage.goals.save(
                    storage.goals.resolve(GOAL_DRAFT).copy(
                        id = GOAL_BOUND,
                        objective = "HXA202 journey bound goal",
                        state = "RUNNING",
                        correlationId = "$GOAL_BOUND-correlation",
                    ),
                )
                storage.goalControls.insert(GoalControlEntity(GOAL_BOUND, SESSION_C, 0, null, null))
                val run = storage.goalRuns.open(RUN_BOUND, GOAL_BOUND, "USER_OPEN", startedAt)
                storage.goalTurnBindings.bind(TURN_C, RUN_BOUND)
                storage.goalRuns.finish(run, "MODEL_COMPLETED", startedAt + 100, 100, 1, 0, 100)
                storage.goals.updateGoal(
                    storage.goals.resolve(GOAL_BOUND).copy(
                        state = "COMPLETED",
                        runCount = 1,
                        modelCalls = 1,
                        totalTokens = 100,
                        runTimeMillis = 100,
                        lastWakeReason = "USER_OPEN",
                        finishReason = "MODEL_COMPLETED",
                    ),
                )
            }
        }
    }

    private fun writeRecoveryMarker(context: Context) {
        context.noBackupFilesDir.resolve(PID_MARKER).writeText(Process.myPid().toString())
    }

    private fun assertSeededJourney(storage: HelixStorage) {
        listOf(SESSION_A, SESSION_B, SESSION_C).forEach { sessionId ->
            val turns = storage.turns.listBySession(sessionId)
            assertEquals(1, turns.size)
            assertEquals("COMPLETED", turns.single().state)
        }
        assertEquals("DRAFT", storage.goals.resolve(GOAL_DRAFT).state)
        assertEquals("COMPLETED", storage.goals.resolve(GOAL_BOUND).state)
        assertEquals(SESSION_A, storage.goalControls.find(GOAL_DRAFT)?.sessionId)
        assertEquals(SESSION_C, storage.goalControls.find(GOAL_BOUND)?.sessionId)
        assertEquals("MODEL_COMPLETED", storage.goalRuns.resolve(RUN_BOUND).outcome)
        assertEquals(RUN_BOUND, storage.goalTurnBindings.byTurn(TURN_C)?.runId)
        val query = ArtifactQuery(storage)
        assertEquals(listOf(ART_A1, ART_A2), query.forTurn(TURN_A).map { it.id })
        assertEquals(listOf(ART_B1), query.forTurn(TURN_B).map { it.id })
        assertEquals(listOf(ART_C1), query.forGoal(GOAL_BOUND).map { it.id })
        assertTrue("an unactivated goal has no artifacts", query.forGoal(GOAL_DRAFT).isEmpty())
    }

    // ---------- dashboard UI helpers ----------

    private fun waitTurnRowsVisible(turnIds: List<String>) {
        turnIds.forEach { scrollToTask("tasks-turn-$it") }
    }

    private fun scrollToTask(tag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("screen-tasks").fetchSemanticsNodes().isNotEmpty()
        }
        // The full suite leaves other history ahead of these rows. LazyColumn only
        // composes visible items; waiting for every fixture row at once cannot work.
        compose.onNodeWithTag("screen-tasks").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun assertOpenOwnChatPage(
        turnId: String,
        sessionId: String,
        chat: ChatService,
    ) {
        scrollToTask("tasks-turn-$turnId")
        compose.onNodeWithTag("tasks-turn-open-$turnId").performScrollTo().performClick()
        compose.waitForIdle()
        stopAwait { chat.screen.value.openSessionId == sessionId }
        compose.onNodeWithTag("screen-sessions").assertIsDisplayed()
        compose.navigateTo("tasks")
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
    }

    /** The activity rebuild path a configuration change goes through, then the same rows back. */
    private fun rebuildDashboardAndWaitForRows() {
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        compose.navigateTo("tasks")
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
    }

    private fun backgroundAndReturnToDashboard() {
        compose.activity.moveTaskToBack(false)
        Thread.sleep(1_500)
        val intent = compose.activity.intent
        if (intent != null) compose.activity.startActivity(intent)
        compose.waitForIdle()
    }

    // ---------- artifact dialog helpers ----------

    private fun assertTurnArtifactOwnership(
        keep: List<String>,
        absent: List<String>,
    ) {
        scrollToTask("tasks-turn-$TURN_A")
        compose.onNodeWithTag("tasks-turn-artifacts-$TURN_A").performScrollTo().performClick()
        compose.waitForIdle()
        assertArtifactDialogRows(keep, absent)
    }

    private fun assertGoalArtifactOwnership(
        goalId: String,
        keep: List<String>,
        absent: List<String>,
    ) {
        scrollToTask("tasks-goal-$goalId")
        compose.onNodeWithTag("tasks-goal-artifacts-$goalId").performScrollTo().performClick()
        compose.waitForIdle()
        assertArtifactDialogRows(keep, absent)
    }

    private fun assertArtifactDialogRows(
        keep: List<String>,
        absent: List<String>,
    ) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-artifacts-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        if (keep.isEmpty()) {
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("tasks-artifacts-empty").fetchSemanticsNodes().isNotEmpty()
            }
        } else {
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                keep.all { id -> compose.onAllNodesWithTag("artifact-file-row-$id").fetchSemanticsNodes().isNotEmpty() }
            }
        }
        absent.forEach { id ->
            val leaked = compose.onAllNodesWithTag("artifact-file-row-$id").fetchSemanticsNodes()
            assertTrue("artifact $id must not leak from another task", leaked.isEmpty())
        }
        compose.onNodeWithTag("tasks-artifacts-close").performClick()
        compose.waitForIdle()
        waitTurnRowsVisible(listOf(TURN_A, TURN_B))
    }

    // ---------- stop-race helpers ----------

    /** The settled-row facts a stop must never rewrite after the fact. */
    private data class SettledTurnRow(
        val id: String,
        val state: String,
        val stepCount: Int,
        val endedAt: Long?,
    )

    private fun settledRow(
        storage: HelixStorage,
        turnId: String,
    ): SettledTurnRow {
        val turn = storage.turns.resolve(turnId)
        return SettledTurnRow(turn.id, turn.state, turn.stepCount, turn.endedAt)
    }

    /**
     * Runs one full stop journey in [sessionId]: the sent turn reaches its durable approval,
     * is stopped through the production path, and the durable cancelling window is proven —
     * a 1 ms poller records every persisted state and CANCELLING must be observed before the
     * settlement's CANCELLED (the stop path writes CANCELLING first; the settlement can only
     * commit after the cancellation has propagated through the live loop). A repeated stop
     * (double-click) on the settled turn is then proven a harmless no-op. Returns the settled
     * row facts, which the caller checks a later stop never rewrites.
     */
    private fun stopLiveJourney(
        storage: HelixStorage,
        chat: ChatService,
        sessionId: String,
        probe: String,
    ): SettledTurnRow {
        chat.openSession(sessionId)
        chat.setMode(AgentMode.ACT)
        chat.send(probe)
        stopAwait { approvalPresent(storage, sessionId) }
        val turn = storage.turns.listBySession(sessionId).single()
        stopAwait { chat.backgroundTasks.value.any { it.id == turn.id } }
        // The journey is durably live in its tool phase BEFORE the stop. The turn row persists
        // RUNNING_TOOL while the call awaits approval (the user-visible "awaiting approval" is
        // the projection over this row plus the approval row), so the stop path must walk this
        // exact row CANCELLING -> CANCELLED; the wait absorbs any write-order jitter between the
        // approval row and the turn row.
        stopAwait { storage.turns.resolve(turn.id).state == "RUNNING_TOOL" }
        val states = CopyOnWriteArrayList<String>()

        val polling = AtomicBoolean(true)
        val poller =
            Thread {
                var last = ""
                while (polling.get()) {
                    val state = storage.turns.resolve(turn.id).state
                    if (state != last) {
                        states.add(state)
                        last = state
                    }
                    Thread.sleep(1)
                }
            }.also { it.start() }
        try {
            chat.stopTask(turn.id)
            stopAwait { storage.turns.resolve(turn.id).state == "CANCELLED" }
            // The observer's 1 ms poller may lag the main thread on a slow device: the
            // ordering claim is checked only after the observer itself has recorded the
            // terminal transition, so a starved iteration can never drop the final state.
            stopAwait { states.contains("CANCELLED") }
            val cancellingIndex = states.indexOf("CANCELLING")
            val cancelledIndex = states.indexOf("CANCELLED")
            assertTrue(
                "CANCELLING must be persisted before the settlement's CANCELLED, saw $states",
                cancellingIndex >= 0 && cancelledIndex > cancellingIndex,
            )
            // A repeated stop (double-click) on the settled turn is a harmless no-op.
            chat.stopTask(turn.id)
            stopAwait { storage.turns.resolve(turn.id).state == "CANCELLED" }
            assertEquals("CANCELLED", storage.turns.resolve(turn.id).state)
            return settledRow(storage, turn.id)
        } finally {
            polling.set(false)
            poller.join(2_000)
        }
    }

    private fun stopIfLive(
        storage: HelixStorage,
        chat: ChatService,
        sessionId: String,
    ) {
        storage.turns.listBySession(sessionId).firstOrNull()?.let { turn ->
            if (TurnState.valueOf(turn.state).isTerminal) return@let
            chat.stopTask(turn.id)
            stopAwait { storage.turns.resolve(turn.id).state == "CANCELLED" }
        }
    }

    private fun approvalPresent(
        storage: HelixStorage,
        sessionId: String,
    ): Boolean =
        storage.turns.listBySession(sessionId).any { turn ->
            storage.toolCalls.listByTurn(turn.id).any {
                storage.approvals.byToolCall(it.id) != null
            }
        }

    /** Registers a fresh version of the `echo` tool the fixture model always calls (ASK class). */
    private fun registerFixtureEcho(
        container: AppContainer,
        executions: AtomicInteger,
    ) {
        val nextVersion =
            (
                container.toolPipeline.registry
                    .resolveLatest(ToolName("echo"))
                    ?.version
                    ?.value ?: 0
            ) + 1
        val descriptor =
            ToolDescriptor(
                name = ToolName("echo"),
                version =
                    com.helix.core.model
                        .ToolVersion(nextVersion),
                description = "Journey stop fixture",
                inputSchema = JsonObject(emptyMap()),
                outputSchema = JsonObject(emptyMap()),
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L2,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        container.toolPipeline.registry.register(descriptor)
        container.toolPipeline.implementations.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    return ToolExecutorResult.Completed(JsonObject(emptyMap()))
                }
            },
        )
    }

    /** The local loopback provider the stop fixture sends through (no account, no network). */
    private suspend fun createFixtureProvider(
        container: AppContainer,
        port: Int,
        suffix: String,
    ): String {
        val service = container.providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "HXA202 journey stop fixture $suffix",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    MODEL,
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        val probe = service.runConnectionTest(id)
        assertTrue("local fixture probe: $probe", probe is ProbeOutcome.Ok)
        val capability = service.runCapabilityTest(id)
        assertTrue("local capability probe: $capability", capability is ProbeOutcome.Ok)
        return id
    }

    // ---------- shared helpers ----------

    private fun recoveryPhase(): String? = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)

    private fun containerFromApp(): AppContainer =
        (ApplicationProvider.getApplicationContext<Application>() as HelixApplication).appContainer

    private fun journeyCounts(storage: HelixStorage): JourneyCounts =
        JourneyCounts(
            totalTurns = storage.turns.recent(1000).size,
            draftRuns = storage.goalRuns.listByGoal(GOAL_DRAFT).size,
            boundRuns = storage.goalRuns.listByGoal(GOAL_BOUND).size,
            draftState = storage.goals.resolve(GOAL_DRAFT).state,
        )

    /** Polls [condition] every 25 ms for up to 30 s (the HXA-200 fixture's await). */
    private fun stopAwait(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("production state must settle", condition())
    }

    private data class ArtifactSpec(
        val id: String,
        val sessionId: String,
        val turnId: String,
        val relativePath: String,
        val content: String,
    )

    private data class JourneyCounts(
        val totalTurns: Int,
        val draftRuns: Int,
        val boundRuns: Int,
        val draftState: String,
    )

    companion object {
        private const val RECOVERY_PHASE_KEY = "recoveryPhase"
        private const val PID_MARKER = "recovery-device-pid"
        private const val JOURNEY_TITLE = "HXA202 journey"
        private const val MODEL = "fixture-model-a"
        private const val SESSION_A = "hxa202-journey-a"
        private const val SESSION_B = "hxa202-journey-b"
        private const val SESSION_C = "hxa202-journey-c"
        private const val TURN_A = "hxa202-journey-turn-a"
        private const val TURN_B = "hxa202-journey-turn-b"
        private const val TURN_C = "hxa202-journey-turn-c"
        private const val GOAL_DRAFT = "hxa202-journey-goal-draft"
        private const val GOAL_BOUND = "hxa202-journey-goal-bound"
        private const val RUN_BOUND = "hxa202-journey-run-bound"
        private const val ART_A1 = "hxa202-journey-art-a1"
        private const val ART_A2 = "hxa202-journey-art-a2"
        private const val ART_B1 = "hxa202-journey-art-b1"
        private const val ART_C1 = "hxa202-journey-art-c1"
    }
}
