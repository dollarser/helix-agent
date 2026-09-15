package com.helix.app

import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.approval.StorageAuditSink
import com.helix.app.approval.ToolApprovalSettingsModel
import com.helix.app.approval.ToolApprovalSettingsState
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.app.ui.ToolApprovalSettingsSection
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalReason
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-201 device acceptance (verification-matrix row `:app:connectedConsumerDebugAndroidTest`):
 * the standing tool-approval preferences as saved from the 201 SURFACES — the settings model
 * ([ToolApprovalSettingsModel], the single write path the settings UI uses) and the approval
 * card's identity-based `setPreferenceFor` ("save future preference") — drive the PRODUCTION
 * pipeline: real Room, the real dispatcher that re-reads the store before a call starts
 * (ADR-0052 point 7), the real broker.
 *
 * The raw service-write matrix (unset / explicit-ask / invalidated / cancel / late approval /
 * scope precedence) is HXA-200's [ToolApprovalPreferenceDeviceTest] and is reused, not redone:
 * this class only adds what the 201 surfaces change — a settings-stored GLOBAL ASK applying
 * across sessions, a settings-stored ALLOW binding the row's CURRENT contract (a later contract
 * change invalidates it into a visible re-confirm, not a silent allow), a settings-stored DENY
 * blocking at the execution boundary, an ALLOW that never skips the high-risk confirmation, and a
 * card-path DENY stored while a card is pending. The restart test runs in the two-phase
 * protocol, driven with `adb shell am instrument` rather than gradle's connected checks (this
 * AGP uninstalls the app — user data included — immediately after every connected run, so
 * nothing written in a phase-1 gradle run could survive to a phase-2 gradle run):
 *   1. `adb shell am instrument -w -e class com.helix.app.ToolApprovalSettingsDeviceTest
 *      com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner` — the normal phase: the
 *      settings-ALLOW test writes the SharedPreferences fixture (tool identity + app PID) and
 *      the restart test skips its Assume;
 *   2. `adb shell am force-stop com.helix.agent` — a real kill (the instrumentation process IS
 *      the app process);
 *   3. the same command plus `-e hxa201SettingsPhase restart` — the class re-runs in a FRESH
 *      app process over the SAME user data (am instrument never uninstalls): the restart test
 *      passes its Assume and proves the settings-saved ALLOW survived a real process restart.
 * Phase 2 re-runs every test; the fixture writer self-gates (isRestartPhase) so it cannot
 * clobber the phase-1 PID marker. The Compose tests render the real section
 * over the real model in all three languages, dark, large font, small screen and rotated.
 */
@RunWith(AndroidJUnit4::class)
class ToolApprovalSettingsDeviceTest {
    private lateinit var container: AppContainer

    @get:Rule val compose = createComposeRule()

    /** Per-run suffix: the device Room persists across runs — tool names and ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "aset-session"
    private val otherSessionId = "aset-session-2"

    /** Every session this class may have seeded cards into — the @After sweep must settle all of them. */
    private val sweptSessions = setOf(sessionId, otherSessionId)

    private val marker = "hxa201-settings-fixture"
    private val restartPhaseArg = "hxa201SettingsPhase"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        val now = System.currentTimeMillis()
        val sessions =
            listOf(sessionId to "aset session", otherSessionId to "aset session 2")
        val existing = container.storage.sessions.list()
        sessions.forEach { (id, title) ->
            if (existing.none { it.id == id }) {
                container.storage.sessions.create(id, title, null, null, now)
            }
        }
        container.chatService.openSession(sessionId)
    }

    @After
    fun settleAbandonedApprovals() {
        // A test that dies after its card is published (any assertion before its deny) leaves the
        // dispatch BLOCKED in the broker, holding a scheduler slot for the process lifetime. Cancel
        // any approval still pending on this class's sessions and wait for its dispatch to settle.
        sweptSessions.forEach { sid ->
            pendingApprovalIdsOn(sid).forEach { container.toolPipeline.broker.cancel(it) }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (
            System.currentTimeMillis() < deadline &&
            sweptSessions.any { pendingApprovalIdsOn(it).isNotEmpty() }
        ) {
            Thread.sleep(50)
        }
    }

    /** The approval ids still pending (AWAITING_APPROVAL calls) on one of this class's sessions. */
    private fun pendingApprovalIdsOn(sessionId: String): List<String> =
        container.storage.turns
            .listBySession(sessionId)
            .flatMap { turn ->
                container.storage.toolCalls
                    .listByTurn(turn.id)
                    .filter { it.state == ToolCallState.AWAITING_APPROVAL.name }
                    .mapNotNull {
                        container.storage.approvals
                            .byToolCall(it.callId)
                            ?.id
                    }
            }

    /** A local built-in tool, L0 card-free by default, with a recording executor. */
    private fun registerLocalTool(
        name: String,
        baseRisk: RiskLevel = RiskLevel.L0,
        version: Int = 1,
        onExecute: () -> Unit = {},
    ): ToolDescriptor {
        val descriptor =
            ToolDescriptor(
                name = ToolName(name),
                version = ToolVersion(version),
                description = "tool approval settings device test tool",
                inputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                outputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                operationClass =
                    if (baseRisk.requiresApproval) {
                        ToolOperationClass.LOCAL_MUTATION
                    } else {
                        ToolOperationClass.READ_ONLY
                    },
                baseRisk = baseRisk,
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
                    onExecute()
                    return ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
                }
            },
        )
        return descriptor
    }

    /** A registered MCP tool (name, provider label) for the settings search fixture. */
    private fun registerMcpTool(
        name: String,
        serverId: String,
    ): ToolDescriptor {
        val descriptor =
            ToolDescriptor(
                name = ToolName(name),
                version = ToolVersion(1),
                description = "tool approval settings mcp fixture",
                inputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                outputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L1,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.McpOrigin(serverId, "2025-03-26", "0".repeat(64)),
            )
        container.toolPipeline.registry.register(descriptor)
        return descriptor
    }

    /** The settings-model write: GLOBAL scope, the row's current contract for an ALLOW. */
    private fun saveSetting(
        name: String,
        source: String,
        preference: ToolApprovalPreference,
    ): ToolApprovalSettingsModel.Row {
        val model = container.toolApprovalSettings
        return runBlocking {
            model.setPreference(
                requireNotNull(model.rowForIdentity(source, name)) { "tool $name must be a settings row" },
                preference,
            )
        }
    }

    /** Seeds the turn row the tool_calls foreign keys require. */
    private fun ensureTurnIn(
        turnId: String,
        sid: String,
    ) {
        val now = System.currentTimeMillis()
        if (container.storage.turns
                .listBySession(sid)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sid, now)
        }
    }

    /**
     * Runs one dispatch on a worker thread (the broker blocks on the user's decision). The
     * dispatcher trusts the turn's PERSISTED session, so [sid] only seeds the turn.
     */
    private fun dispatchOnThread(
        toolCallId: String,
        turnId: String,
        toolName: String,
        sid: String = sessionId,
    ): DispatchHandle {
        ensureTurnIn(turnId, sid)
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val error = arrayOf<Throwable?>(null)
        val t =
            Thread {
                try {
                    outcome[0] = container.chatService.dispatchToolCall(toolCallId, turnId, toolName, "{}")
                } catch (e: Throwable) {
                    error[0] = e
                } finally {
                    latch.countDown()
                }
            }
        t.isDaemon = true
        t.start()
        return DispatchHandle(latch, outcome, error, t)
    }

    private class DispatchHandle(
        val latch: CountDownLatch,
        val outcome: Array<ToolDispatchOutcome?>,
        val error: Array<Throwable?>,
        val worker: Thread,
    ) {
        fun join(): ToolDispatchOutcome {
            assertTrue("dispatch must finish", latch.await(30, TimeUnit.SECONDS))
            error[0]?.let { throw it }
            return outcome[0] ?: error("no outcome")
        }
    }

    /** Polls the storage-backed approval record — the source of truth for a pending card. */
    private fun approvalIdOf(toolCallId: String): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            container.storage.approvals
                .byToolCall(toolCallId)
                ?.let { return it.id }
            Thread.sleep(50)
        }
        error("no pending approval record for $toolCallId")
    }

    private fun auditFor(callId: String): com.helix.app.approval.DispatchAuditRecord {
        val row =
            container.storage.auditEvents
                .listByCorrelation(callId)
                .single { it.type == "tool_dispatch" }
        return requireNotNull(
            StorageAuditSink.parseRow(
                row.id,
                row.correlationId,
                row.type,
                row.actor,
                row.redactedPayload,
                row.timestamp,
            ),
        )
    }

    // ------------------------------------------------------------------ real execution: a
    // preference saved through the settings model drives the production pipeline.

    @Test
    fun aSettingsStoredAskShowsACardInEverySession() {
        val descriptor = registerLocalTool("aset.ask.$run")
        val source = descriptor.origin.canonicalOf()
        val row = saveSetting(descriptor.name.value, source, ToolApprovalPreference.ASK)
        assertEquals(ToolApprovalSettingsState.ASK, row.state)
        assertEquals(ToolApprovalPreferenceScope.GLOBAL, row.records.single().scope)
        // GLOBAL scope: the settings-stored restriction applies to a call from ANOTHER session too.
        listOf(sessionId, otherSessionId).forEach { sid ->
            val callId = "aset-ask-call-$sid-$run"
            val handle = dispatchOnThread(callId, "aset-ask-turn-$sid-$run", descriptor.name.value, sid)
            val approvalId = approvalIdOf(callId)
            container.chatService.denyApproval(approvalId)
            val outcome = handle.join() as ToolDispatchOutcome.Denied
            assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
        }
    }

    @Test
    fun aSettingsStoredAllowRunsTheLowRiskCallCardFree() {
        val descriptor = registerLocalTool("aset.allow.$run")
        val source = descriptor.origin.canonicalOf()
        val row = saveSetting(descriptor.name.value, source, ToolApprovalPreference.ALLOW)
        assertEquals(ToolApprovalSettingsState.ALLOW, row.state)
        val callId = "aset-allow-call-$run"
        val outcome = dispatchOnThread(callId, "aset-allow-turn-$run", descriptor.name.value).join()
        assertTrue(
            "an allowed in-scope low-risk call must run: $outcome",
            outcome is ToolDispatchOutcome.Succeeded,
        )
        assertNull(
            "an allowed card-free call must not publish a card",
            container.storage.approvals.byToolCall(callId),
        )
        val audit = auditFor(callId)
        assertEquals("ALLOW", audit.preferenceAtStart!!.effective)
        // The restart fixture (marker + this tool's ALLOW) is written only in the NORMAL phase:
        // phase 2 of the restart protocol re-runs the whole class (class KDoc), and a fresh
        // PID written here would clobber the phase-1 marker the restart test asserts against.
        if (!isRestartPhase()) {
            writeRestartFixture(descriptor, source)
        }
    }

    @Test
    fun aSettingsStoredDenyBlocksTheCallWithoutACard() {
        val executions = AtomicInteger()
        val descriptor = registerLocalTool("aset.deny.$run") { executions.incrementAndGet() }
        val source = descriptor.origin.canonicalOf()
        val row = saveSetting(descriptor.name.value, source, ToolApprovalPreference.DENY)
        assertEquals(ToolApprovalSettingsState.DENY, row.state)
        val callId = "aset-deny-call-$run"
        val outcome = dispatchOnThread(callId, "aset-deny-turn-$run", descriptor.name.value).join()
        assertEquals(
            DispatchOutcomeCode.PREFERENCE_DENIED,
            (outcome as ToolDispatchOutcome.Denied).code,
        )
        assertEquals(0, executions.get())
        assertNull(
            "a DENY block must not publish a card",
            container.storage.approvals.byToolCall(callId),
        )
    }

    @Test
    fun aSettingsStoredAllowOnAHighRiskToolStillShowsTheCard() {
        val executions = AtomicInteger()
        val descriptor = registerLocalTool("aset.hi.$run", baseRisk = RiskLevel.L2) { executions.incrementAndGet() }
        val source = descriptor.origin.canonicalOf()
        val row = saveSetting(descriptor.name.value, source, ToolApprovalPreference.ALLOW)
        assertEquals(ToolApprovalSettingsState.ALLOW, row.state)
        // An ALLOW never skips the high-risk confirmation: the card is still presented.
        val callId = "aset-hi-call-$run"
        val handle = dispatchOnThread(callId, "aset-hi-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        container.chatService.approveApproval(approvalId)
        assertTrue(handle.join() is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executions.get())
    }

    @Test
    fun aContractChangeInvalidatesTheSettingsAllowIntoReConfirm() {
        val name = "aset.contract.$run"
        registerLocalTool(name)
        val source = ToolOrigin.BuiltInOrigin.canonicalOf()
        val allowed = saveSetting(name, source, ToolApprovalPreference.ALLOW)
        assertEquals(ToolApprovalSettingsState.ALLOW, allowed.state)
        // A newer version of the tool changes its contract hash: the stored ALLOW is invalidated
        // at read time, and the row visibly asks for a RE-CONFIRM (the provenance is preserved).
        registerLocalTool(name, version = 2)
        val invalidated = requireNotNull(container.toolApprovalSettings.rowForIdentity(source, name))
        assertEquals(2, invalidated.version)
        assertEquals(ToolApprovalSettingsState.ASK_INVALIDATED, invalidated.state)
        assertEquals(ToolApprovalPreference.ALLOW, invalidated.records.single().preference)
        val callId = "aset-contract-call-$run"
        val handle = dispatchOnThread(callId, "aset-contract-turn-$run", name)
        val approvalId = approvalIdOf(callId)
        container.chatService.denyApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
        val audit = auditFor(callId)
        assertEquals(ToolApprovalReason.ALLOW_INVALIDATED, audit.preferencePresented!!.source)
        assertEquals(
            false,
            audit.preferencePresented
                .rules
                .single()
                .contractValid,
        )
    }

    @Test
    fun aDenySavedFromTheCardIdentityPathWhilePendingBlocksEvenThePresentedCard() {
        val executions = AtomicInteger()
        val descriptor = registerLocalTool("aset.pending.$run") { executions.incrementAndGet() }
        val source = descriptor.origin.canonicalOf()
        saveSetting(descriptor.name.value, source, ToolApprovalPreference.ASK)
        val callId = "aset-pending-call-$run"
        val handle = dispatchOnThread(callId, "aset-pending-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        // The approval card's "save future preference" path: an identity-based GLOBAL write.
        val settings = container.toolApprovalSettings
        val updated =
            runBlocking { settings.setPreferenceFor(source, descriptor.name.value, ToolApprovalPreference.DENY) }
        assertEquals(ToolApprovalSettingsState.DENY, updated?.state)
        // A DENY stored while the card is pending blocks even the user's pending approve.
        container.chatService.approveApproval(approvalId)
        val outcome = handle.join()
        assertEquals(DispatchOutcomeCode.PREFERENCE_DENIED, (outcome as ToolDispatchOutcome.Denied).code)
        assertEquals(0, executions.get())
        // The next call is blocked at the boundary, card-free.
        val next =
            dispatchOnThread("aset-pending-call2-$run", "aset-pending-turn2-$run", descriptor.name.value).join()
        assertEquals(DispatchOutcomeCode.PREFERENCE_DENIED, (next as ToolDispatchOutcome.Denied).code)
    }

    // ------------------------------------------------------------------ real execution: the
    // settings-saved preference survives a REAL process restart (two-phase protocol).

    @Test
    fun aSettingsStoredAllowSurvivesARealProcessRestart() {
        org.junit.Assume.assumeTrue(
            "requires the two-phase restart protocol (normal run, force-stop, phase re-run)",
            isRestartPhase(),
        )
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val fixture = app.getSharedPreferences(marker, Context.MODE_PRIVATE)
        val tool = requireNotNull(fixture.getString("tool", null)) { "run the normal phase first" }
        val source = requireNotNull(fixture.getString("source", null))
        assertNotEquals("must be a fresh app process", fixture.getInt("pid", -1), Process.myPid())
        // The in-memory registry is empty in the fresh process; re-register the same tool.
        registerLocalTool(tool)
        val row = requireNotNull(container.toolApprovalSettings.rowForIdentity(source, tool))
        assertEquals(
            "the settings-stored ALLOW must have survived the restart",
            ToolApprovalSettingsState.ALLOW,
            row.state,
        )
        assertEquals(ToolApprovalPreferenceScope.GLOBAL, row.records.single().scope)
        val callId = "aset-restart-call-$run"
        val outcome = dispatchOnThread(callId, "aset-restart-turn-$run", tool).join()
        assertTrue(
            "the allowed call must run card-free after the restart: $outcome",
            outcome is ToolDispatchOutcome.Succeeded,
        )
        assertNull(container.storage.approvals.byToolCall(callId))
        assertTrue(fixture.edit().clear().commit())
    }

    @Test fun selectedSessionDenyDoesNotLeakToAnotherSession() {
        val descriptor = registerLocalTool("aset.session.$run")
        val model = container.toolApprovalSettings
        val selected = model.scopeChoices().single { it.sessionId == sessionId }
        val row = model.rows(selection = selected).single { it.toolName == descriptor.name.value }
        runBlocking { model.setPreference(row, ToolApprovalPreference.DENY) }
        assertTrue(
            dispatchOnThread("scope-deny-$run", "scope-turn-$run", row.toolName).join() is ToolDispatchOutcome.Denied,
        )
        assertTrue(
            dispatchOnThread(
                "scope-other-$run",
                "scope-other-turn-$run",
                row.toolName,
                otherSessionId,
            ).join() is ToolDispatchOutcome.Succeeded,
        )
        runBlocking { model.restoreDefault(row) }
        assertTrue(
            dispatchOnThread(
                "scope-reset-$run",
                "scope-reset-turn-$run",
                row.toolName,
            ).join() is ToolDispatchOutcome.Succeeded,
        )
    }

    @Test fun workspaceRestrictionIsResolvedFromThePersistedSession() {
        val descriptor = registerLocalTool("aset.workspace.$run")
        val model = container.toolApprovalSettings
        val selected = model.scopeChoices().single { it.sessionId == sessionId }
        val workspace =
            model.scopeChoices().single {
                it.scope == ToolApprovalPreferenceScope.WORKSPACE &&
                    it.ref == selected.workspaceRef
            }
        val row = model.rows(selection = workspace).single { it.toolName == descriptor.name.value }
        runBlocking { model.setPreference(row, ToolApprovalPreference.DENY) }
        // A context absent from this repository must not acquire a guessed default workspace.
        assertTrue(
            container.toolApprovalPreferenceService
                .snapshotFor(
                    descriptor.origin.canonicalOf(),
                    descriptor.name.value,
                    descriptor.contractHash.hex,
                    "missing-session-$run",
                    null,
                ).records
                .isEmpty(),
        )
        assertTrue(
            dispatchOnThread(
                "workspace-deny-$run",
                "workspace-turn-$run",
                row.toolName,
            ).join() is ToolDispatchOutcome.Denied,
        )
        container.storage.sessions.updateDetails(otherSessionId, "other workspace", "app:other")
        try {
            assertTrue(
                dispatchOnThread(
                    "workspace-other-$run",
                    "workspace-other-turn-$run",
                    row.toolName,
                    otherSessionId,
                ).join() is ToolDispatchOutcome.Succeeded,
            )
        } finally {
            container.storage.sessions.updateDetails(otherSessionId, "other session", null)
        }
        val sessionRow = model.rows(selection = selected).single { it.toolName == row.toolName }
        assertEquals(
            ToolApprovalSettingsState.DENY,
            runBlocking {
                model.setPreference(sessionRow, ToolApprovalPreference.ALLOW)
            }.state,
        )
        runBlocking { model.restoreDefault(row) }
        assertTrue(
            dispatchOnThread(
                "workspace-reset-$run",
                "workspace-reset-turn-$run",
                row.toolName,
            ).join() is ToolDispatchOutcome.Succeeded,
        )
    }

    @Test fun oldCardAllowReportsChangedContractAndWritesNothing() {
        val descriptor = registerLocalTool("aset.oldcard.$run")
        val model = container.toolApprovalSettings
        registerLocalTool(descriptor.name.value, version = 2)
        val notice =
            runBlocking {
                com.helix.app.ui.savePreferenceNotice {
                    model.setPreferenceFor(
                        descriptor.origin.canonicalOf(),
                        descriptor.name.value,
                        ToolApprovalPreference.ALLOW,
                        descriptor.contractHash.hex,
                    )
                }
            }
        assertEquals(R.string.preference_save_changed, notice)
        assertEquals(
            ToolApprovalSettingsState.UNSET,
            model.rowForIdentity(descriptor.origin.canonicalOf(), descriptor.name.value)?.state,
        )
    }

    @Test fun selectedScopeUiSavesAndResetsOnlyThatSession() {
        val descriptor = registerLocalTool("aset.select.$run")
        val selected = container.toolApprovalSettings.scopeChoices().single { it.sessionId == sessionId }
        renderSectionViewport(
            container.toolApprovalSettings,
            mutableStateOf(AppLanguageStore.localeListFor(AppLanguage.EN)),
            mutableStateOf(400),
            mutableStateOf(false),
            mutableStateOf(1f),
        )
        compose.onNodeWithTag("preference-scope-picker", useUnmergedTree = true).performScrollTo().performClick()
        compose
            .onNodeWithTag("preference-scope-${selected.key}", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        val name = descriptor.name.value
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(
                    SemanticsMatcher("scoped row ready") {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tool-approval-$name-deny"
                    },
                    true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("tool-approval-$name-deny", useUnmergedTree = true).performScrollTo().performClick()
        awaitRowState("tool-approval-state-$name", "Deny: hidden from the model; calls are blocked")
        captureSettings("selected-session")
        assertEquals(
            ToolApprovalPreferenceScope.SESSION,
            container.toolApprovalSettings
                .rows(selection = selected)
                .single {
                    it.toolName ==
                        name
                }.records
                .single()
                .scope,
        )
        assertTrue(
            dispatchOnThread(
                "ui-scope-other-$run",
                "ui-scope-turn-$run",
                name,
                otherSessionId,
            ).join() is ToolDispatchOutcome.Succeeded,
        )
        compose.onNodeWithTag("tool-approval-$name-reset", useUnmergedTree = true).performScrollTo().performClick()
        awaitRowState("tool-approval-state-$name", "Unset: follows the default policy")
    }

    @Test fun realChatCardRejectsStaleAllowAndReportsSavedDenyWithoutExecuting() {
        val descriptor = registerLocalTool("aset.realcard.$run")
        val source = descriptor.origin.canonicalOf()
        saveSetting(descriptor.name.value, source, ToolApprovalPreference.ASK)
        val callId = "realcard-call-$run"
        val handle = dispatchOnThread(callId, "realcard-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        lateinit var target: Context
        compose.setContent {
            target = LocalContext.current
            MaterialTheme {
                com.helix.app.ui.ChatScreen(
                    container.chatService,
                    container.providerService,
                    container.privacyDeletionService,
                    toolApprovalSettings = container.toolApprovalSettings,
                )
            }
        }
        compose.waitForIdle()
        registerLocalTool(descriptor.name.value, version = 2)
        compose
            .onNodeWithTag(
                "approval-future-allow-$approvalId",
                useUnmergedTree = true,
            ).performScrollTo()
            .performClick()
        awaitRowState("chat-preference-save-notice", target.getString(R.string.preference_save_changed))
        assertEquals(
            ToolApprovalSettingsState.ASK,
            container.toolApprovalSettings.rowForIdentity(source, descriptor.name.value)?.state,
        )
        assertNull(
            container.storage.approvals
                .byToolCall(callId)
                ?.decision,
        )
        compose
            .onNodeWithTag(
                "approval-future-deny-$approvalId",
                useUnmergedTree = true,
            ).performScrollTo()
            .performClick()
        awaitRowState("chat-preference-save-notice", target.getString(R.string.settings_tool_approval_state_deny))
        assertNull(
            container.storage.approvals
                .byToolCall(callId)
                ?.decision,
        )
        captureSettings("real-card-save-feedback")
        container.chatService.approveApproval(approvalId)
        assertTrue(handle.join() is ToolDispatchOutcome.Denied)
    }

    /** True in phase 2 of the restart protocol (the whole-class re-run after the force-stop). */
    private fun isRestartPhase(): Boolean =
        InstrumentationRegistry
            .getArguments()
            .getString(restartPhaseArg) == "restart"

    private fun writeRestartFixture(
        descriptor: ToolDescriptor,
        source: String,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(
            context
                .getSharedPreferences(marker, Context.MODE_PRIVATE)
                .edit()
                .putString("tool", descriptor.name.value)
                .putString("source", source)
                .putString("session", sessionId)
                .putInt("pid", Process.myPid())
                .commit(),
        )
    }

    // ------------------------------------------------------------------ Compose UI: the real
    // settings section over the real model (the same instance the production settings screen
    // uses), in all three languages, dark, large font, small screen and rotated.
    //
    // Every compose test renders the section ONCE (setContent may be called only once per test)
    // in a fixed-WIDTH scrollable viewport that fills the test window's height; locale, width,
    // dark and font scale are SNAPSHOT STATE the composable reads, so re-rendering means
    // mutating the state. The window's measured height flakes across installs (full screen vs.
    // a small initial size — observed 842dp vs 112dp on the same AVD), so every visibility- or
    // click-sensitive assertion first scrolls the node INTO that viewport (viewport == window,
    // so a scrolled-in node is displayed) — the established pattern of ApprovalLayoutDeviceTest.
    // All tag-based finders use the UNMERGED tree: tag-only nodes (a row's Column) do not
    // reliably survive semantics merging, while tagged text and clickable buttons do.

    @Test
    fun theSettingsSectionRendersSearchAndStateCopyInAllThreeLanguages() {
        val builtin = registerLocalTool("asetui.list.$run")
        val mcp = registerMcpTool("mcp.asetsui.probe", "asetsui-srv-$run")
        val builtinName = builtin.name.value
        val mcpName = mcp.name.value
        val cases =
            listOf(
                Triple(
                    LocaleListCompat.forLanguageTags("zh"),
                    "工具审批偏好",
                    "未设置：按默认策略处理",
                ),
                Triple(
                    AppLanguageStore.localeListFor(AppLanguage.ZH_CN),
                    "工具审批偏好",
                    "未设置：按默认策略处理",
                ),
                Triple(
                    AppLanguageStore.localeListFor(AppLanguage.EN),
                    "Tool approval preferences",
                    "Unset: follows the default policy",
                ),
            )
        val locales = mutableStateOf(cases.first().first)
        renderSectionViewport(
            container.toolApprovalSettings,
            locales,
            width = mutableStateOf(400),
            dark = mutableStateOf(false),
            fontScale = mutableStateOf(1f),
        )
        cases.forEach { (caseLocales, title, unsetCopy) ->
            locales.value = caseLocales
            compose.waitForIdle()
            compose.onNodeWithText(title).fetchSemanticsNode()
            compose
                .onNodeWithTag("tool-approval-state-$builtinName", useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
                .assertTextEquals(unsetCopy)
            compose
                .onNodeWithTag("tool-approval-row-$mcpName", useUnmergedTree = true)
                .fetchSemanticsNode()
            compose
                .onNodeWithTag("tool-approval-$mcpName-deny", useUnmergedTree = true)
                .fetchSemanticsNode()
        }
    }

    @Test
    fun theSettingsSectionSearchFiltersByNameAndProvider() {
        val builtin = registerLocalTool("asetui.filter.$run")
        val mcp = registerMcpTool("mcp.asetsui.filter", "asetsui-srv-$run")
        val locales = mutableStateOf(AppLanguageStore.localeListFor(AppLanguage.ZH_CN))
        renderSectionViewport(
            container.toolApprovalSettings,
            locales,
            width = mutableStateOf(400),
            dark = mutableStateOf(false),
            fontScale = mutableStateOf(1f),
        )
        compose
            .onNodeWithTag("settings-tool-approval-search", useUnmergedTree = true)
            .performScrollTo()
            .performTextInput("mcp")
        // The filter re-resolves the rows on an IO dispatcher: wait until it has settled.
        // Both sides are checked on the UNMERGED tree (tag-only row nodes do not reliably
        // survive semantics merging, so the merged tree could make "absent" a vacuous pass).
        val mcpRowMatcher =
            SemanticsMatcher("mcp row after filter") {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tool-approval-row-${mcp.name.value}"
            }
        val builtinRowMatcher =
            SemanticsMatcher("builtin row after filter") {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tool-approval-row-${builtin.name.value}"
            }
        compose.waitUntil("the mcp row must be the only row after the filter", 10_000) {
            compose
                .onAllNodes(mcpRowMatcher, true)
                .fetchSemanticsNodes()
                .size == 1 &&
                compose
                    .onAllNodes(builtinRowMatcher, true)
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
    }

    @Test
    fun theSettingsSectionStaysUsableInDarkLargeFontSmallScreenAndRotated() {
        val descriptor = registerLocalTool("asetui.layout.$run")
        val name = descriptor.name.value
        val locales = mutableStateOf(AppLanguageStore.localeListFor(AppLanguage.ZH_CN))
        val width = mutableStateOf(360)
        val dark = mutableStateOf(true)
        val fontScale = mutableStateOf(2f)
        renderSectionViewport(container.toolApprovalSettings, locales, width, dark, fontScale)
        // Dark + large font + a narrow 360dp portrait viewport (a small phone).
        compose
            .onNodeWithTag("tool-approval-row-$name", useUnmergedTree = true)
            .performScrollTo()
        val viewport =
            compose
                .onNodeWithTag("aset-viewport", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        listOf("allow", "ask", "deny", "reset").forEach { action ->
            val tag = "tool-approval-$name-$action"
            val node =
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .performScrollTo()
                    .assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "$tag must fit horizontally in the small screen: $bounds",
                bounds.left >= viewport.left && bounds.right <= viewport.right,
            )
        }
        captureSettings("small-dark-large-font")
        // "Rotated" to a 640dp landscape viewport: the same row and actions stay reachable,
        // and a real click works (the write goes through the model off the main thread).
        width.value = 640
        compose.waitForIdle()
        compose
            .onNodeWithTag("tool-approval-row-$name", useUnmergedTree = true)
            .performScrollTo()
        compose
            .onNodeWithTag("tool-approval-$name-deny", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitRowState("tool-approval-state-$name", "禁止：不提供给模型，调用被阻止")
        // Restore the default: the row visibly goes back to UNSET (the test's own cleanup).
        compose
            .onNodeWithTag("tool-approval-$name-reset", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitRowState("tool-approval-state-$name", "未设置：按默认策略处理")
    }

    @Test
    fun savingFromTheSettingsUiRoundTripsToRoomAndBack() {
        val descriptor = registerLocalTool("asetui.save.$run")
        val name = descriptor.name.value
        val source = descriptor.origin.canonicalOf()
        val locales = mutableStateOf(AppLanguageStore.localeListFor(AppLanguage.EN))
        renderSectionViewport(
            container.toolApprovalSettings,
            locales,
            width = mutableStateOf(400),
            dark = mutableStateOf(false),
            fontScale = mutableStateOf(1f),
        )
        compose
            .onNodeWithTag("tool-approval-$name-deny", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitRowState("tool-approval-state-$name", "Deny: hidden from the model; calls are blocked")
        // The click went through the single write service into real Room:
        assertEquals(
            EffectiveToolPreference.Deny,
            container.toolApprovalPreferenceService
                .effectiveFor(source, name, descriptor.contractHash.hex, sessionId, null),
        )
        assertEquals(
            ToolApprovalSettingsState.DENY,
            container.toolApprovalSettings.rowForIdentity(source, name)?.state,
        )
        // Restore default removes the record and shows the re-resolved state.
        compose
            .onNodeWithTag("tool-approval-$name-reset", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitRowState("tool-approval-state-$name", "Unset: follows the default policy")
        assertEquals(
            EffectiveToolPreference.Unset,
            container.toolApprovalPreferenceService
                .effectiveFor(source, name, descriptor.contractHash.hex, sessionId, null),
        )
    }

    /**
     * The UI writes are async (Compose scope → IO dispatcher → Room → the section's refetch),
     * so poll the re-resolved row-state copy instead of assuming it settled by the next idle.
     */
    private fun captureSettings(name: String) {
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(target.filesDir, "hxa201-captures").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            java.io.File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitRowState(
        tag: String,
        expectedCopy: String,
    ) {
        // The UI writes are async (Compose scope → IO dispatcher → Room → the section's
        // refetch); the rule's waitUntil pumps the main thread while polling and fails the
        // test (with the message) on timeout.
        compose.waitUntil("row state must reach $expectedCopy (waiting on $tag)", 15_000) {
            compose
                .onAllNodes(
                    SemanticsMatcher("matching state") {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag &&
                            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
                                text.text ==
                                    expectedCopy
                            }
                    },
                    true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * The single setContent of one test. Every parameter is SNAPSHOT STATE: a test that
     * re-renders (a locale switch, a "rotation" to landscape width) mutates the state and the
     * one composition re-renders; a test that never re-renders just passes one-shot state.
     *
     * The section fetches its rows on an IO dispatcher (a LaunchedEffect), so an idle
     * composition does NOT mean the rows are in the tree yet — the first fetch can still be
     * running (worst on the process's first Room touch). Every test registers at least one
     * tool before rendering, so the helper waits until the first fetch has settled.
     */
    private fun renderSectionViewport(
        model: ToolApprovalSettingsModel,
        locales: State<LocaleListCompat>,
        width: State<Int>,
        dark: State<Boolean>,
        fontScale: State<Float>,
    ) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val context = AppLanguageStore.wrapForLocale(target, locales.value)
            val density = LocalDensity.current
            val scheme = if (dark.value) darkColorScheme() else MaterialTheme.colorScheme
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                MaterialTheme(colorScheme = scheme) {
                    androidx.compose.material3.Surface {
                        Column(
                            Modifier
                                .width(width.value.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .testTag("aset-viewport"),
                        ) { ToolApprovalSettingsSection(model) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil("the section's first row fetch must settle", 15_000) {
            compose
                .onAllNodes(
                    SemanticsMatcher("settled rows") {
                        it.config
                            .getOrElse(SemanticsProperties.TestTag) { "" }
                            .startsWith("tool-approval-row-")
                    },
                    true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
