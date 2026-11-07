package com.helix.app.chat

import com.helix.app.foreground.DataSyncForegroundController
import com.helix.app.proot.BackgroundJobAction
import com.helix.app.proot.BackgroundJobActions
import com.helix.app.proot.BackgroundJobUi
import com.helix.app.proot.ProotToolModule
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.DashboardRefreshPort
import com.helix.feature.conversation.presentation.GoalSummaryUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One owner for dashboard read models. Reads do not admit, resume or settle executions. */
class TaskDashboardProjection internal constructor(
    private val storage: HelixStorage,
    private val scope: CoroutineScope,
    private val turnGate: Any,
    private val clock: Clock,
    private val hasHandoff: () -> Boolean,
) : DashboardRefreshPort {
    private val taskRows = MutableStateFlow<List<BackgroundTaskUi>>(emptyList())
    val tasks = taskRows.asStateFlow()
    private val jobRows = MutableStateFlow<List<BackgroundJobUi>>(emptyList())
    val jobs = jobRows.asStateFlow()
    private val transport = MutableStateFlow<TurnState?>(null)
    override val foregroundTransport = transport.asStateFlow()
    private val goalRows = MutableStateFlow<List<GoalSummaryUi>>(emptyList())
    val goals = goalRows.asStateFlow()
    private val planRows = MutableStateFlow<List<PlanRowUi>>(emptyList())
    internal val plans = planRows.asStateFlow()
    private val artifactRows = MutableStateFlow<List<ArtifactRowUi>>(emptyList())
    internal val artifacts = artifactRows.asStateFlow()
    internal val jobActions =
        BackgroundJobActions(scope, ProotToolModule::performBackgroundJobAction) {
            refreshBackground()
            refreshTasks()
        }

    override fun refreshBackground() =
        synchronized(turnGate) {
            val rows = BackgroundTaskQuery(storage).read()
            taskRows.value = rows
            jobRows.value = ProotToolModule.backgroundJobs(storage)
            transport.value = rows
                .firstOrNull {
                    !it.awaitingApproval && it.state in DataSyncForegroundController.TRANSPORT_ACTIVE
                }?.state ?: if (hasHandoff()) TurnState.BUILDING_CONTEXT else null
        }

    override suspend fun refreshTasks() =
        withContext(Dispatchers.IO) {
            goalRows.value = GoalSummaryQuery(storage).forAll()
            planRows.value = PlanRowQuery(storage).read()
            artifactRows.value = ArtifactQuery(storage).recent(50)
        }

    val jobActionState get() = jobActions.state

    fun performBackgroundJobAction(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
    ) = jobActions.submit(job, action)

    /** Persist the explicit acknowledgement before refreshing the task feed. */
    fun collectResult(turnId: String) =
        scope.launch {
            storage.turns.collectResult(turnId, clock.now().toEpochMilli())
            refreshBackground()
        }

    fun runningTurnIdsForSession(sessionId: String): List<String> =
        BackgroundTaskQuery(storage)
            .read()
            .filter { it.sessionId == sessionId && it.running }
            .map { it.id }

    fun refreshBackgroundNow() = scope.launch { refreshBackground() }

    fun refreshTasksNow() = scope.launch { refreshTasks() }

    override fun onTurnChanged(
        turnId: String,
        state: TurnState,
    ) {
        if (taskRows.value.none { it.id == turnId && it.state == state }) {
            refreshBackground()
            refreshTasksNow()
        }
    }
}
