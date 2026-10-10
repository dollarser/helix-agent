package com.helix.app.chat

import com.helix.app.foreground.DataSyncForegroundController
import com.helix.app.proot.BackgroundJobActions
import com.helix.app.proot.BackgroundJobUi
import com.helix.app.proot.ProotToolModule
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One owner for dashboard read models. Reads do not admit, resume or settle executions. */
internal class TaskDashboardProjection(
    private val storage: HelixStorage,
    private val scope: CoroutineScope,
    private val turnGate: Any,
    private val hasHandoff: () -> Boolean,
) {
    private val taskRows = MutableStateFlow<List<BackgroundTaskUi>>(emptyList())
    val tasks = taskRows.asStateFlow()
    private val jobRows = MutableStateFlow<List<BackgroundJobUi>>(emptyList())
    val jobs = jobRows.asStateFlow()
    private val transport = MutableStateFlow<TurnState?>(null)
    val foregroundTransport = transport.asStateFlow()
    private val goalRows = MutableStateFlow<List<GoalSummaryUi>>(emptyList())
    val goals = goalRows.asStateFlow()
    private val planRows = MutableStateFlow<List<PlanRowUi>>(emptyList())
    val plans = planRows.asStateFlow()
    private val artifactRows = MutableStateFlow<List<ArtifactRowUi>>(emptyList())
    val artifacts = artifactRows.asStateFlow()
    val jobActions =
        BackgroundJobActions(scope, ProotToolModule::performBackgroundJobAction) {
            refreshBackground()
            refreshTasks()
        }

    fun refreshBackground() =
        synchronized(turnGate) {
            val rows = BackgroundTaskQuery(storage).read()
            taskRows.value = rows
            jobRows.value = ProotToolModule.backgroundJobs(storage)
            transport.value = rows
                .firstOrNull {
                    !it.awaitingApproval && it.state in DataSyncForegroundController.TRANSPORT_ACTIVE
                }?.state ?: if (hasHandoff()) TurnState.BUILDING_CONTEXT else null
        }

    suspend fun refreshTasks() =
        withContext(Dispatchers.IO) {
            goalRows.value = GoalSummaryQuery(storage).forAll()
            planRows.value = PlanRowQuery(storage).read()
            artifactRows.value = ArtifactQuery(storage).recent(50)
        }
}
