package com.helix.app.proot

import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Scripted model output, real Provider stream, Goal admission, Dispatcher and Runtime. */
internal class DetachedGoalFixture(
    private val app: HelixApplication,
    private val server: ScriptedTaskModelServer,
) {
    val container = app.appContainer
    val storage = container.storage
    private val previousProfile = container.profileStore.profile
    private var provider: String? = null
    lateinit var session: String
    lateinit var goal: String
    var turn = ""

    @Volatile var leaseId: String? = null
    val output = File(app.filesDir, "workspaces/app/output/goal-${UUID.randomUUID()}.txt")

    suspend fun prepare() {
        ensureInstalledRuntime(app)
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        check(ProotToolModule.verifyNow() is ProotRuntimeAvailability.Verified)
        val id =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "Detached Goal fixture",
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
        provider = id
        check(container.providerService.runConnectionTest(id) is ProbeOutcome.Ok)
        session = container.chatService.createSession("Detached Goal fixture", id, "fixture-model-a")
        storage.sessionPermissionConfigs.setForSession(
            session,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            System.currentTimeMillis(),
        )
        container.chatService.openSession(session)
    }

    suspend fun submit(collectInModel: Boolean = true) {
        goal =
            container.chatService.createGoal(
                "Produce the original Job result",
                emptyList(),
                GoalBudgets(10, 10, 100000, 120000, 60000, 0),
            )
        server.arm(
            listOf(
                ScriptedTaskModelServer.Step(DetachedJobTools.START) {
                    buildJsonObject {
                        put("script", "sleep 1; printf goal-result > result.txt")
                        put("output", "scope:app:output/${output.name}")
                        put("leaseSeconds", 20)
                    }.toString()
                },
                ScriptedTaskModelServer.Step("goal.report") { report },
                ScriptedTaskModelServer.Step(DetachedJobTools.COLLECT) { originalResult() },
                ScriptedTaskModelServer.Step("goal.report") { report },
            ).let { if (collectInModel) it else it.take(2) },
        )
        turn =
            container.agentRuntime
                .submit(
                    SubmitTurnCommand(
                        session = SessionId(session),
                        providerId = ProviderId(requireNotNull(provider)),
                        mode = AgentMode.GOAL,
                        text = "Run the detached command, collect its original result, then report completion.",
                        budgets = TurnBudgets(10, 8, 65536, 4096, 100000),
                        goalId = GoalId(goal),
                        clientRequestId = UUID.randomUUID().toString(),
                        continuousGoal = false,
                    ),
                ).value
    }

    private val report = """{"status":"complete","summary":"Original result verified"}"""

    fun originalResult(): String {
        val currentTurn = storage.turns.listBySession(session).single()
        val call = storage.toolCalls.listByTurn(currentTurn.id).single { it.name == DetachedJobTools.START }
        val binding = ProotJobBindingStore(storage).resolveDetached(session, call.callId)
        val run = storage.goalRuns.listByGoal(goal).single()
        leaseId =
            storage.goalUsageReservations
                .pendingForRun(run.id)
                .single { it.kind == "TIME_LEASE" }
                .id
        check(storage.goals.resolve(goal).state == "RUNNING")
        val client = DetachedJobClient(app)
        val until = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (client
                    .query(binding)
                    .record
                    ?.state
                    ?.isTerminal == true
            ) {
                return buildJsonObject { put("originalCallId", call.callId) }.toString()
            }
            Thread.sleep(100)
        }
        error("Original Goal Job did not terminate")
    }

    suspend fun close() {
        if (turn.isNotEmpty()) container.chatService.stopTask(turn)
        container.chatService.closeSession()
        container.profileStore.switchTo(previousProfile)
        provider?.let { container.providerService.delete(it) }
        output.delete()
    }
}
