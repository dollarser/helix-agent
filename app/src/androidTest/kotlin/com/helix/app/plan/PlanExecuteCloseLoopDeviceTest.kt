package com.helix.app.plan

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.AppContainer
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * HXA-192 device acceptance: the real Plan review -> execution UI loop. The sibling
 * PlanSubmitIntegrationDeviceTest proves the production `plan.submit` tool/Room (the 提交计划
 * precondition) and TasksDashboardDeviceTest proves the dashboard display + cancel decision;
 * NEITHER proves the execute closed loop, which is what this class adds.
 *
 * A review-required (READY) plan is opened in the Tasks dashboard review dialog and EXECUTED
 * through the production dialog button (approvePlan -> executeApprovedPlan). The closed loop
 * asserts the durable facts agree:
 *
 * - the plan row moves to EXECUTING and its evidenceRef becomes the executing goal's id;
 * - exactly ONE goal is created, bound to the EXACT approved plan id + version hash (never a
 *   second goal, never a drifted version — the goal's planHash equals the reviewed version's hash);
 * - the goal's turn is attributed to the session that executed it (turn/session ownership);
 * - a successful execute dismisses the review surface.
 *
 * Revise returns the plan to DRAFT (re-drivable), completing the 执行 / 修改 / 拒绝 decision set
 * (cancel is covered by TasksDashboardDeviceTest). A loopback model provider (no external
 * network) is bound to the executing session so the production executeApprovedPlan provider gates
 * pass; the model is scripted to answer immediately so the plan-bound turn settles without holding
 * a scheduler slot or a foreground service.
 */
class PlanExecuteCloseLoopDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun executingAReadyPlanThroughTheReviewDialogCreatesTheBoundGoalAndClosesTheLoop() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val planId = "plan-exec-${System.nanoTime()}"
            val fixture = openLoopbackSession(container)
            try {
                seedReadyPlan(container, planId)

                // Open the review dialog for the specific version and execute it through the button.
                compose.navigateTo("tasks")
                await {
                    container.chatService.planDashboard.value
                        .any { it.id == planId }
                }
                compose.onNodeWithTag("screen-tasks").performScrollToNode(hasTestTag("tasks-plan-$planId"))
                compose.onNodeWithTag("tasks-plan-$planId").assertExists()
                compose.onNodeWithTag("tasks-plan-review-$planId").performClick()
                // reviewPlan is a suspend Room read; a single waitForIdle() returns as soon as the
                // frame clock stops, which can be before the dialog's review state lands and its
                // nodes compose. Poll for the dialog root instead.
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("plan-review-$planId").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("plan-execute-$planId").assertExists()
                compose.onNodeWithTag("plan-execute-$planId").performClick()
                // The execute button launches approve -> execute on Dispatchers.Main.immediate: each
                // service call hops to IO and resumes back on the main looper, so this wait must PUMP
                // that looper (compose.waitUntil) or the flow freezes right after approve (stuck at
                // APPROVED, no blockedReason). Gate on the in-memory dashboard projection the service
                // refreshes once the plan commits EXECUTING, then on the dialog dismissal.
                awaitExecuting(compose, container, planId)
                // A successful execute dismisses the review surface.
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("plan-review-$planId").fetchSemanticsNodes().isEmpty()
                }
                val planEntity = container.storage.plans.resolveEntity(planId)
                val goalId =
                    checkNotNull(planEntity.evidenceRef) { "an executed plan must bind its executing goal" }
                val goalEntity = container.storage.goals.resolveEntity(goalId)
                assertEquals(planId, goalEntity.planId)
                assertEquals(
                    "the goal must bind the EXACT reviewed version's hash",
                    planEntity.hash,
                    goalEntity.planHash,
                )
                assertEquals(
                    "one plan execution creates exactly one goal",
                    1,
                    container.storage.goals
                        .list()
                        .count { it.planId == planId },
                )
                // Turn/session attribution: the goal's turn is owned by the executing session.
                await {
                    container.storage.goalControls
                        .find(goalId)
                        ?.sessionId == fixture.session
                }
            } finally {
                chat.stopContinuousGoals()
                chat.closeSession()
                container.providerService.delete(fixture.provider)
                fixture.server.close()
            }
        }

    @Test
    fun reviseThroughTheReviewDialogReturnsThePlanToDraft() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val planId = "plan-rev-${System.nanoTime()}"
            val fixture = openLoopbackSession(container)
            try {
                seedReadyPlan(container, planId)
                compose.navigateTo("tasks")
                await {
                    container.chatService.planDashboard.value
                        .any { it.id == planId }
                }
                compose.onNodeWithTag("screen-tasks").performScrollToNode(hasTestTag("tasks-plan-$planId"))
                compose.onNodeWithTag("tasks-plan-review-$planId").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("plan-revise-$planId").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("plan-revise-$planId").performClick()
                compose.waitForIdle()
                await {
                    container.storage.plans
                        .resolveEntity(planId)
                        .state == "DRAFT"
                }
            } finally {
                chat.closeSession()
                container.providerService.delete(fixture.provider)
                fixture.server.close()
            }
        }

    // ---------------------------------------------------------------- fixtures

    /** A live loopback provider + a new open session bound to it (the executing session). */
    private data class LoopbackFixture(
        val server: LoopbackModelServer,
        val provider: String,
        val session: String,
    )

    private suspend fun openLoopbackSession(container: AppContainer): LoopbackFixture {
        val server = LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED)
        server.start()
        val provider =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "Plan execute fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", server.port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
        server.scriptedChat = { answerSse() }
        val session = container.chatService.createSession("Plan execute fixture", provider, "fixture-model-a")
        container.chatService.openSession(session)
        await { container.chatService.screen.value.openSessionId == session }
        return LoopbackFixture(server, provider, session)
    }

    private fun seedReadyPlan(
        container: AppContainer,
        planId: String,
    ) {
        container.storage.withTransaction {
            container.storage.plans.save(
                PlanArtifact(
                    id = PlanId(planId),
                    objective = "Plan execute device test",
                    assumptions = emptyList(),
                    steps = listOf(PlanStep("step one", "do the thing")),
                    acceptanceCriteria = listOf("the thing is done"),
                    risks = emptyList(),
                    version = 1,
                ),
                "READY",
                null,
            )
        }
    }

    /** An immediate, tool-free SSE answer so the plan-bound turn settles at once. */
    private fun answerSse(): String =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"}," +
            "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private suspend fun await(condition: () -> Boolean) =
        withContext(Dispatchers.IO) {
            withTimeout(60_000) { while (!condition()) delay(50) }
        }

    /**
     * Waits for the executed plan to reach EXECUTING by PUMPING the main looper
     * (compose.waitUntil), which is what lets the dialog's Main.immediate approve -> execute
     * coroutine make progress. On timeout it reports the stuck state plus any UI block, so a
     * failure pinpoints where the loop broke: "stuck at APPROVED" = the execute hop never ran
     * (looper starved); "blockedReason=..." = a provider gate refused.
     */
    private suspend fun awaitExecuting(
        compose: AndroidComposeTestRule<*, *>,
        container: AppContainer,
        planId: String,
    ) {
        try {
            compose.waitUntil(60_000) {
                container.chatService.planDashboard.value
                    .firstOrNull { it.id == planId }
                    ?.state == "EXECUTING"
            }
        } catch (e: AssertionError) {
            val stuck =
                withContext(Dispatchers.IO) {
                    container.storage.plans
                        .resolveEntity(planId)
                        .state
                }
            val blocked = container.chatService.screen.value.blockedReason
            throw AssertionError(
                "plan did not reach EXECUTING after execute; stuck at $stuck, blockedReason=$blocked",
                e,
            )
        }
    }
}
