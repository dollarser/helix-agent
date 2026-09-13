package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.CleartextBindingStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.agent.CancelResult
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.StoredGoal
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceOpener
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Device acceptance for the UNIFIED CANCEL SETTLEMENT (research doc section 34; HX2-01): a parked
 * (INTERRUPTED) turn's cancel goes through the SAME settlement the live unwind commits — the
 * terminal row and the goal settlement in one real Room transaction, followed by the shared
 * post-settlement cleanup, page refresh and reminder sync. And the runtime contract reports the
 * two cancel outcomes differently:
 *
 * - a parked turn has no live loop: [AppAgentRuntime.cancel] settles it to CANCELLED before it
 *   returns and reports [CancelResult.Cancelled] (settled NOW — not a stop request, which would
 *   be [CancelResult.StopAccepted]);
 * - a bound goal that process-restart already recovered (PAUSED goal, closed run) is NOT
 *   re-settled: the goal row, the run row and the audit trail all keep their first outcome;
 * - re-cancelling the settled turn is the contract's idempotent [CancelResult.AlreadyTerminal];
 * - the open session's page refreshes to the CANCELLED row, and the turn's
 *   [com.helix.core.agent.AgentRuntime.observe] stream ends on the terminal — a parked turn's
 *   observers are never left hanging.
 *
 * The model never runs: the wire fails fast and no turn is ever submitted — the fixtures write
 * the post-recovery rows directly, exactly what the recovery coordinator (HXA-015) leaves behind.
 */
@RunWith(AndroidJUnit4::class)
class ParkedTurnCancelSettlementDeviceTest {
    @Test
    fun parkedTurnCancelSettlesNowRefreshesThePageAndIsIdempotentToReCancel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = newFixture(context)
        try {
            val turnId = seedParkedTurn(fixture.storage)
            fixture.service.openSession(SESSION_ID)
            val runtime = AppAgentRuntime(fixture.service)
            await("the open session page shows the parked turn") {
                fixture.service.screen.value.turns
                    .singleOrNull()
                    ?.state == TurnState.INTERRUPTED
            }

            val result = runBlocking { runtime.cancel(TurnId(turnId)) }
            assertTrue(
                "a parked cancel is settled now, not a stop request",
                result is CancelResult.Cancelled,
            )

            val turn = fixture.storage.turns.resolve(turnId)
            assertEquals(TurnState.CANCELLED.name, turn.state)
            assertNotNull(turn.endedAt)

            // The open session's page was refreshed to the settled row (the cancel's refresh is
            // synchronous, and the parked row was already on screen — nothing stale follows it).
            assertEquals(
                TurnState.CANCELLED,
                fixture.service.screen.value.turns
                    .single()
                    .state,
            )

            // The turn's observers still receive the terminal — the stream ENDS, it does not
            // hang for a parked turn that never went through the live unwind.
            val frames = runBlocking { runtime.observe(TurnId(turnId)).toList() }
            assertEquals(TurnState.CANCELLED, frames.last().phase)
            assertTrue(frames.last().isTerminal)

            // Cancelling an already-settled turn is the idempotent no-op the contract defines.
            val again = runBlocking { runtime.cancel(TurnId(turnId)) }
            assertTrue(again is CancelResult.AlreadyTerminal && again.phase == TurnState.CANCELLED)
        } finally {
            settleAndClose(fixture)
        }
    }

    @Test
    fun parkedGoalTurnCancelDoesNotResettleTheRecoveredGoal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = newFixture(context)
        try {
            val (turnId, goalId, runId) = seedParkedGoalTurn(fixture.storage)
            val goalBefore = fixture.storage.goals.resolve(goalId)
            val runBefore = fixture.storage.goalRuns.resolve(runId)
            val finishedRunsBefore = finishedRunAudits(fixture.storage)

            val result = runBlocking { AppAgentRuntime(fixture.service).cancel(TurnId(turnId)) }
            assertTrue(result is CancelResult.Cancelled)
            assertEquals(
                TurnState.CANCELLED.name,
                fixture.storage.turns
                    .resolve(turnId)
                    .state,
            )

            // The already-recovered goal and its closed run are untouched: the settle inside the
            // cancel transaction hits its guard and preserves the first outcome.
            assertEquals(goalBefore, fixture.storage.goals.resolve(goalId))
            assertEquals(runBefore, fixture.storage.goalRuns.resolve(runId))
            assertEquals(
                "no second goal.run_finished may be audited for the recovered goal",
                finishedRunsBefore,
                finishedRunAudits(fixture.storage),
            )
        } finally {
            settleAndClose(fixture)
        }
    }

    // --- fixtures: the post-recovery rows the recovery coordinator (HXA-015) leaves behind ---

    /** A session with one parked (INTERRUPTED) turn and no goal. */
    private fun seedParkedTurn(storage: HelixStorage): String {
        storage.sessions.create(SESSION_ID, "Parked cancel", PROVIDER_ID, "model-x", 1_000)
        val turn = storage.turns.start(TURN_ID, SESSION_ID, 1_000)
        // The process-death edge: any non-terminal state parks INTERRUPTED.
        storage.turns.updateState(turn, TurnState.INTERRUPTED, turn.stepCount, null, null)
        return TURN_ID
    }

    /**
     * A parked goal turn: the run was open and bound when the process died; recovery closed the
     * run with outcome INTERRUPTED and parked the goal in PAUSED (the turn parked INTERRUPTED).
     */
    private fun seedParkedGoalTurn(storage: HelixStorage): Triple<String, String, String> {
        storage.sessions.create(SESSION_ID, "Parked goal cancel", PROVIDER_ID, "model-x", 1_000)
        storage.goals.save(
            StoredGoal(
                id = GOAL_ID,
                objective = "Settle a parked cancel exactly once",
                criteria = emptyList(),
                budgets = GoalBudgets(10, 20, 100_000, 600_000, 60_000, 0),
                state = GoalState.RUNNING.name,
                planId = null,
                planHash = null,
                nextCheckpoint = null,
                correlationId = CORRELATION_ID,
                runCount = 1,
                modelCalls = 0,
                toolCalls = 0,
                totalTokens = 0,
                runTimeMillis = 0,
                currentWakeMillis = 0,
                retries = 0,
                lastWakeReason = "USER_OPEN",
                error = null,
                finishReason = null,
            ),
        )
        storage.goalRuns.open(RUN_ID, GOAL_ID, "USER_OPEN", 1_000)
        TurnCoordinator.start(
            storage,
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(1_000)
            },
            { "id-$RUN_ID" },
            TurnStartSpec(SESSION_ID, TURN_ID, "model-call-1", "snapshot", "hello", goalRunId = RUN_ID),
        )
        // Recovery rows: the turn parks, the run closes INTERRUPTED, the goal parks PAUSED.
        val parked = storage.turns.resolve(TURN_ID)
        storage.turns.updateState(parked, TurnState.INTERRUPTED, parked.stepCount, null, null)
        storage.goalRuns.finish(storage.goalRuns.resolve(RUN_ID), "INTERRUPTED", 2_000, 0, 0, 0, 0)
        storage.goals.updateGoal(storage.goals.resolve(GOAL_ID).copy(state = GoalState.PAUSED.name))
        return Triple(TURN_ID, GOAL_ID, RUN_ID)
    }

    private fun finishedRunAudits(storage: HelixStorage): Int =
        storage.auditEvents
            .listByCorrelation(CORRELATION_ID)
            .count { it.type == "goal.run_finished" }

    /** Polls [condition] from the test thread with a hard deadline. */
    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("timed out waiting for: $what")
    }

    /** One isolated service stack per test: real Room, fake wire (the model never runs). */
    private data class Fixture(
        val storage: HelixStorage,
        val service: ChatService,
    )

    private fun newFixture(context: Context): Fixture {
        val suffix = UUID.randomUUID().toString()
        val storage =
            HelixStorage.open(
                context,
                "parked-cancel-$suffix.db",
                File(context.filesDir, "parked-cancel-$suffix"),
            )
        val workspaceRoot = File(context.filesDir, "parked-cancel-ws-$suffix").apply { mkdirs() }
        val lineStore = InMemoryLineStore()
        val statusStore = ProviderTestStatusStore(lineStore)
        val providerService = newProviderService(storage, lineStore, statusStore, suffix)
        seedProvider(storage, statusStore)
        val app = context.applicationContext as HelixApplication
        // HXA-069: ChatService is pure JVM and resolves its emitted string-resource IDs through
        // the injected `strings` seam; a zh-CN resolver keeps the emitted copy deterministic.
        val zh =
            AppLanguageStore.wrapForLocale(
                context.applicationContext,
                AppLanguageStore.localeListFor(AppLanguage.ZH_CN),
            )
        val service =
            ChatService(
                storage = storage,
                providerService = providerService,
                profileStore = FixedStandardProfileStore,
                toolPipeline = app.appContainer.toolPipeline,
                idGenerator = { "id-${UUID.randomUUID()}" },
                attachmentStaging =
                    AttachmentStagingSupport(
                        importer =
                            AttachmentImporter(
                                SafImportPipeline(
                                    scopeRoots = ScopeRootResolver { _ -> workspaceRoot.toPath() },
                                    opener = SafSourceOpener { error("device test: no attachments are staged") },
                                ),
                            ),
                        workspaceScopeId = "cancel-scope",
                        sourceMetadata = { error("device test: no attachments are staged") },
                        resolveWorkspacePath = { error("device test: no attachments are staged") },
                    ),
                strings = { resId, args -> zh.getString(resId, *args) },
            )
        return Fixture(storage, service)
    }

    /** The provider stack over a wire that throws — the tests only need a valid, tested row. */
    private fun newProviderService(
        storage: HelixStorage,
        lineStore: InMemoryLineStore,
        statusStore: ProviderTestStatusStore,
        suffix: String,
    ): ProviderService =
        ProviderService(
            storage = storage,
            factory =
                ProviderFactory(
                    credentials = CredentialLookup { ProviderFactory.NO_KEY_PLACEHOLDER },
                    wire =
                        object : WireClient {
                            override suspend fun open(request: WireRequest): WireResponse =
                                throw IOException(
                                    "device test: the wire is disabled — no network",
                                )
                        },
                    imageSource = {
                        com.helix.app.provider.VisionImageSource {
                            throw IllegalArgumentException(
                                "device test: image resolution is disabled — no artifacts",
                            )
                        }
                    },
                ),
            bindings = CleartextBindingStore(lineStore),
            testStatus = statusStore,
            idGenerator = { "prov-$suffix" },
        )

    /** Seeds the keyless HTTPS provider row and records a passed connection test for it. */
    private fun seedProvider(
        storage: HelixStorage,
        statusStore: ProviderTestStatusStore,
    ): ProviderConfigSpec {
        val spec =
            ProviderConfigSpec(
                id = PROVIDER_ID,
                displayName = "Parked Cancel Test Provider",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://one.invalid/v1",
                model = "model-x",
                headersJson = "{}",
                secretAlias = ProviderFactory.NO_KEY_ALIAS,
                capabilitySnapshot = "untested",
            )
        storage.providerConfigs.save(spec)
        statusStore.recordPassed(
            PROVIDER_ID,
            System.currentTimeMillis(),
            ProviderCapabilities(
                streaming = true,
                toolCalls = false,
                parallelToolCalls = false,
                vision = false,
                reasoning = false,
                jsonSchemaOutput = false,
                maxContextTokens = null,
                source = CapabilitySource.PROBED,
            ),
        )
        return spec
    }

    /** The service only observes the profile flow; the tests pin STANDARD. */
    private object FixedStandardProfileStore : SafetyProfileStore {
        override val profile: SafetyProfile = SafetyProfile.STANDARD
        override val flow: StateFlow<SafetyProfile> = MutableStateFlow(SafetyProfile.STANDARD)

        override fun switchTo(profile: SafetyProfile) = error("profile switching is not under test")
    }

    /** Gives the service's jobs a beat to release storage handles before the DB closes. */
    private fun settleAndClose(fixture: Fixture) {
        Thread.sleep(SETTLE_MILLIS)
        fixture.storage.close()
    }

    private companion object {
        const val SESSION_ID = "parked-cancel-session"
        const val TURN_ID = "parked-cancel-turn"
        const val GOAL_ID = "parked-cancel-goal"
        const val RUN_ID = "parked-cancel-run"
        const val CORRELATION_ID = "corr-parked-cancel"
        const val PROVIDER_ID = "prov-parked-cancel"
        const val AWAIT_TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 50L
        const val SETTLE_MILLIS = 500L
    }
}
