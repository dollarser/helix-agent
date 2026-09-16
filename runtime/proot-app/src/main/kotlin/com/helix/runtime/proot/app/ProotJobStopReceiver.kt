package com.helix.runtime.proot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The 通知停止 action target (HXA-086): the stop button of the running-job
 * notification broadcasts here (same uid via the immutable PendingIntent) and
 * the receiver cancels the job through the runner's normal cancel path —
 * process-group kill, terminal CANCELLED record, notification removed on the
 * terminal path.
 *
 * ADR-0049: the explicit immutable PendingIntent targets a non-exported receiver
 * in the same application and runtime process. It can only cancel an existing job.
 */
class ProotJobStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID)
        if (jobId.isNullOrEmpty()) return
        if (!jobId.startsWith("job_") || jobId.length != JOB_ID_LENGTH) return
        // The runner cancels only jobs of THIS process's journal; an unknown
        // id is a no-op (store.load returns null).
        ProotJobRunner.get(context.applicationContext).cancel(jobId)
    }

    private companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val JOB_ID_LENGTH = 16 // "job_" + 12 lowercase hex
    }
}
