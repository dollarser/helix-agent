package com.helix.app.ui

import com.helix.app.chat.BackgroundTaskUi
import com.helix.core.model.TurnState

/** Missing session identity must never fall back to all conversations. */
internal fun sessionTasks(
    tasks: List<BackgroundTaskUi>,
    sessionId: String?,
): List<BackgroundTaskUi> = tasks.filter { sessionId != null && it.sessionId == sessionId }

/** Failed/cancelled executions remain in Tasks; they are not completed deliverables. */
internal fun isDeliverableResult(task: BackgroundTaskUi): Boolean = task.state == TurnState.COMPLETED
