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
 * exported=true is REQUIRED for the PendingIntent to resolve (an implicit
 * broadcast to a non-exported receiver is not deliverable), and the
 * SIGNATURE-level permission (the same one that guards the service and the
 * repair activity) restricts senders to the same signed set: the main app and
 * the developer test APK. A job cancel is the only effect; there is no
 * capability minting here (no job submission, no file access, no state read).
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
