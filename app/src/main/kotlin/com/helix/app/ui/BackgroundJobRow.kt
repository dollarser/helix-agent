package com.helix.app.ui

import com.helix.app.R
import com.helix.app.proot.BackgroundJobUi
import com.helix.app.proot.CommandDetailState

/** A Goal/Turn completion never deduplicates or settles its detached command. */
internal data class BackgroundJobRow(
    val job: BackgroundJobUi,
) : TasksRow {
    override val key: String get() = "job-${job.callId}"
    override val testTag: String get() = "tasks-job-${job.callId}"
    override val title: String get() = job.title
    override val kindRes: Int get() = R.string.tasks_kind_job
    override val statusRes: Int get() = commandDetailStateLabel(job.state)
    override val bucket: TasksBucket
        get() =
            when {
                job.settlementPending -> TasksBucket.NEEDS_YOU
                job.state == CommandDetailState.SUCCEEDED -> TasksBucket.COMPLETED
                job.state in setOf(CommandDetailState.FAILED, CommandDetailState.CANCELLED) -> TasksBucket.FAILED
                else -> TasksBucket.NEEDS_YOU
            }
}
