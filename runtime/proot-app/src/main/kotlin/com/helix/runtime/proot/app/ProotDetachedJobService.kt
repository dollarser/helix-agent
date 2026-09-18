package com.helix.runtime.proot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.helix.runtime.proot.core.DetachedLease
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.DetachedJobProtocol
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobRefusal
import com.helix.runtime.proot.ipc.ProotJobSubmitResult
import com.helix.runtime.proot.ipc.ProotJobWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit detached execution only; binding/querying alone never starts a foreground owner. */
class ProotDetachedJobService : Service() {
    private val generation = UUID.randomUUID().toString()
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var runner: ProotJobRunner
    private lateinit var store: DetachedJobStore

    @Volatile private var admission: Admission? = null

    private class Admission(
        val record: DetachedJobStore.Record,
    ) {
        val gate =
            com.helix.runtime.proot.core
                .JobAdmission()
        val token: String = UUID.randomUUID().toString()
        val ready = CountDownLatch(1)

        @Volatile var promoted = false

        @Volatile var submitted = false

        fun isReady(
            ready: Boolean,
            remaining: Long,
        ): Boolean = ready && promoted && remaining >= DetachedLease.MIN_MS
    }

    override fun onCreate() {
        super.onCreate()
        runner = ProotJobRunner.get(this)
        store = DetachedJobStore(ProotJobStore(ProotRuntimeInstaller.runtimeRoot(this)))
        monitor.scheduleWithFixedDelay({ tick() }, 100, 100, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent): IBinder = endpoint

    @Suppress("SwallowedException")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val current = admission
        if (current == null || intent?.getStringExtra("ticket") != current.token) {
            if (current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NOTIFICATION_ID, notification(current.record.binding.jobId), type)
            if (admission === current) {
                current.promoted = true
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        } catch (_: SecurityException) {
            stopSelf(startId)
        } catch (_: IllegalStateException) {
            stopSelf(startId)
        } finally {
            current.ready.countDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        admission?.let {
            it.gate.cancel { runner.cancel(it.record.binding.jobId) }
            runner.releaseDetached(it.record.binding.jobId)
            it.ready.countDown()
        }
        admission = null
        monitor.shutdownNow()
        super.onDestroy()
    }

    private val endpoint =
        object : Binder() {
            @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (reply == null) return false
                if (!ProotCallerVerifier.verify(this@ProotDetachedJobService, getCallingUid())) {
                    reject(reply, ProotJobRefusal.INVALID_SPEC)
                    return true
                }
                try {
                    data.enforceInterface(DetachedJobProtocol.DESCRIPTOR)
                    val binding = DetachedJobProtocol.readBinding(data)
                    when (code) {
                        DetachedJobProtocol.SUBMIT -> submit(binding, data, reply)
                        DetachedJobProtocol.QUERY, DetachedJobProtocol.CANCEL -> control(binding, code, data, reply)
                        else -> reject(reply, ProotJobRefusal.INVALID_SPEC)
                    }
                } catch (_: Exception) {
                    reject(reply, ProotJobRefusal.INVALID_SPEC)
                }
                return true
            }
        }

    @Synchronized
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "LongMethod")
    private fun submit(
        binding: DetachedJobBinding,
        data: Parcel,
        reply: Parcel,
    ) {
        val budget = data.readLong()
        val spec = ProotJobWire.readSpec(data)
        val (input, output) = ProotJobWire.readPfds(data)
        var handedOff = false
        try {
            require(data.dataAvail() == 0 && binding.matches(spec))
            val hash = DetachedJobStore.requestHash(spec)
            val previous = store.read(binding.jobId)
            if (previous != null) {
                require(previous.binding == binding && previous.requestHash == hash)
                writeDuplicate(reply, runner.query(binding.jobId))
                return
            }
            require(runner.query(binding.jobId) == null) { "Job identity already belongs to another execution" }
            if (admission != null || !runner.reserveDetached(binding.jobId)) {
                reject(reply, ProotJobRefusal.EXECUTION_BUSY)
                return
            }
            val lease =
                DetachedLease.create(
                    generation,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),
                    spec.deadlineMs,
                    budget,
                )
            val current = Admission(DetachedJobStore.Record(binding, lease, hash))
            admission = current
            store.write(current.record)
            val identity = Binder.clearCallingIdentity()
            try {
                startForegroundService(
                    Intent(this, ProotDetachedJobService::class.java).putExtra("ticket", current.token),
                )
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
            val ready = current.ready.await(4, TimeUnit.SECONDS)
            val remaining = lease.remainingMs(generation, SystemClock.elapsedRealtime())
            if (!current.isReady(ready, remaining)) {
                release(current)
                reject(reply, ProotJobRefusal.BACKGROUND_UNAVAILABLE)
                return
            }
            val result =
                current.gate.start {
                    handedOff = true
                    runner.submitDetached(spec.copy(deadlineMs = remaining), input, output)
                }
            when (result) {
                null -> {
                    release(current)
                    reject(reply, ProotJobRefusal.CANCELLED_BEFORE_START)
                }

                is ProotJobSubmitResult.Accepted -> {
                    current.submitted = true
                    writeRecord(reply, ProotRuntimeProtocol.REPLY_JOB_ACCEPTED, result.record)
                }

                is ProotJobSubmitResult.Duplicate -> {
                    release(current)
                    writeRecord(reply, ProotRuntimeProtocol.REPLY_JOB_DUPLICATE, result.record)
                }

                is ProotJobSubmitResult.Rejected -> {
                    release(current)
                    reject(reply, result.refusal)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            settleFailedSubmission(binding, handedOff)
            reject(reply, ProotJobRefusal.BACKGROUND_UNAVAILABLE)
        } catch (_: Exception) {
            settleFailedSubmission(binding, handedOff)
            reject(reply, ProotJobRefusal.BACKGROUND_UNAVAILABLE)
        } finally {
            if (!handedOff) {
                input.close()
                output.close()
            }
        }
    }

    private fun settleFailedSubmission(
        binding: DetachedJobBinding,
        handedOff: Boolean,
    ) {
        val current = admission?.takeIf { it.record.binding == binding }
        if (handedOff && runner.query(binding.jobId) != null) {
            current?.submitted = true
            runner.cancel(binding.jobId)
        } else {
            current?.let(::release)
            runner.releaseDetached(binding.jobId)
        }
    }

    private fun control(
        binding: DetachedJobBinding,
        code: Int,
        data: Parcel,
        reply: Parcel,
    ) {
        require(data.dataAvail() == 0)
        require(store.read(binding.jobId)?.binding == binding)
        val record =
            if (code ==
                DetachedJobProtocol.CANCEL
            ) {
                val current = admission?.takeIf { it.record.binding == binding }
                if (current == null) {
                    runner.cancel(binding.jobId)
                } else {
                    current.gate.cancel { runner.cancel(binding.jobId) }
                }
            } else {
                runner.query(binding.jobId)
            }
        val status =
            if (record ==
                null
            ) {
                ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND
            } else {
                ProotRuntimeProtocol.REPLY_JOB_STATE
            }
        ProotJobWire.writeJobReply(reply, status, record?.let(ProotJobRecordCodec::encode))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tick() {
        val current = admission ?: return
        if (!current.submitted) return
        try {
            val record = runner.query(current.record.binding.jobId)
            if (record?.state?.isTerminal == true) {
                release(current)
            } else if (current.record.lease.remainingMs(generation, SystemClock.elapsedRealtime()) == 0L) {
                runner.expireDetached(current.record.binding.jobId)
            }
        } catch (failure: Exception) {
            android.util.Log.e("DetachedJob", "Owner observation failed", failure)
            runner.cancel(current.record.binding.jobId)
        }
    }

    @Synchronized
    private fun release(current: Admission) {
        if (admission !== current) return
        runner.releaseDetached(current.record.binding.jobId)
        store.discardUnsubmitted(current.record.binding.jobId)
        admission = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reject(
        reply: Parcel,
        reason: ProotJobRefusal,
    ) = ProotJobWire.writeJobReply(reply, ProotRuntimeProtocol.REPLY_JOB_REJECTED, reason.wire)

    companion object {
        private const val NOTIFICATION_ID = 196
    }
}

private const val CHANNEL = "proot-detached"

private fun ProotDetachedJobService.notification(jobId: String): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL,
            getString(R.string.proot_job_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
    val stop =
        ProotJobNotification
            .stopBroadcastIntent(
                this,
                jobId,
            ).setClass(this, ProotJobStopReceiver::class.java)
    val action =
        PendingIntent.getBroadcast(
            this,
            jobId.hashCode(),
            stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    return Notification
        .Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_helix)
        .setContentTitle(getString(R.string.proot_job_running_title))
        .setContentText(jobId)
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, getString(R.string.proot_job_stop), action).build())
        .build()
}

private fun writeRecord(
    reply: Parcel,
    status: Byte,
    record: com.helix.runtime.proot.ipc.ProotJobRecord,
) = ProotJobWire.writeJobReply(reply, status, ProotJobRecordCodec.encode(record))

private fun writeDuplicate(
    reply: Parcel,
    record: com.helix.runtime.proot.ipc.ProotJobRecord?,
) {
    if (record == null) {
        ProotJobWire.writeJobReply(
            reply,
            ProotRuntimeProtocol.REPLY_JOB_REJECTED,
            ProotJobRefusal.BACKGROUND_UNAVAILABLE.wire,
        )
    } else {
        writeRecord(reply, ProotRuntimeProtocol.REPLY_JOB_DUPLICATE, record)
    }
}
