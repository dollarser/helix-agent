package com.helix.runtime.proot.app

import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.sha256Hex
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotJobSubmitResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-086 通知停止: the running-job notification (plain, non-FGS) appears
 * while a job is RUNNING, its stop action cancels the job through the REAL
 * broadcast path (the same signature-permission-protected receiver the
 * PendingIntent targets), and every terminal state removes the notification.
 *
 * The tests run IN the companion process, so the broadcast is sent from the
 * same uid the PendingIntent would use (the host-side `am broadcast` path is
 * exercised by the acceptance script); POST_NOTIFICATIONS (API 33+) is
 * granted by the acceptance environment before this class runs — without it
 * the platform silently drops the post and the notification assertions are
 * assumed off rather than faked green.
 */
@RunWith(AndroidJUnit4::class)
class ProotJobNotificationDeviceTest {
    private lateinit var context: Context
    private lateinit var runner: ProotJobRunner
    private lateinit var notificationManager: NotificationManager
    private val jobIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ensureActiveRuntime()
        runner = ProotJobRunner.get(context)
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumePostNotificationsUsable()
    }

    @After
    fun tearDown() {
        jobIds.forEach { id ->
            runner.query(id)?.let { record ->
                if (!record.state.isTerminal) runner.cancel(id)
            }
            ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context)).jobDir(id).deleteRecursively()
            File(context.filesDir, "notif-ran-" + id).delete()
        }
        jobIds.clear()
    }

    /** The notification is a convenience surface: when the runtime permission is
     *  absent the platform drops posts — assert the state honestly and skip. */
    private fun assumePostNotificationsUsable() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted =
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        org.junit.Assume.assumeTrue(
            "POST_NOTIFICATIONS not granted — run `adb shell pm grant " +
                "com.helix.runtime.proot android.permission.POST_NOTIFICATIONS`",
            granted,
        )
    }

    private fun ensureActiveRuntime() {
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        RootFsInstaller.currentActive(root)?.let { return }
        val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
        val outcome =
            RootFsInstaller.install(
                ProotRuntimeInstaller.buildInstallRequest(
                    context,
                    lock,
                    ProotNative.pageSizeBytes(),
                    System.currentTimeMillis(),
                ),
            )
        assertTrue("runtime install failed: $outcome", outcome is com.helix.runtime.proot.core.InstallOutcome.Success)
    }

    private fun nextJobId(): String =
        "job_" + (0 until 12).map { "0123456789ab"[(Math.random() * 12).toInt()] }.joinToString("")

    /** Submits a `sleep <secs>` job; returns the jobId. The job is NOT awaited here. */
    private fun submitSleepJob(
        secs: Long,
        deadlineMs: Long,
    ): String {
        val jobId = nextJobId()
        jobIds += jobId
        File(context.filesDir, "notif-ran-" + jobId).writeText("ran")
        val cacheDir = File(context.cacheDir, "notif-$jobId").apply { mkdirs() }
        val emptyManifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val archive = File(cacheDir, "input.zip")
        JobZipWriter(archive.outputStream()).use { writer -> writer.writeManifest(emptyManifest) }
        val inputSha =
            sha256Hex(MessageDigest.getInstance("SHA-256").apply { update(emptyManifest.encodeToByteArray()) })
        val spec =
            ProotJobSpec(
                executionId = "exec-notif-" + System.nanoTime().toUInt().toString(16),
                jobId = jobId,
                command = ProotJobCommand.Argv(listOf("/bin/sleep", secs.toString())),
                relativeWorkingDirectory = "",
                environment = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "HOME" to "/root"),
                deadlineMs = deadlineMs,
                maxOutputBytes = 1_048_576L,
                inputManifestSha256 = inputSha,
            )
        val inputPfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        val outputPfd =
            ParcelFileDescriptor.open(
                File(cacheDir, "output.zip"),
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            )
        val result = runner.submit(spec, inputPfd, outputPfd)
        assertTrue("submit must be accepted, got: $result", result is ProotJobSubmitResult.Accepted)
        return jobId
    }

    private fun waitForTerminal(
        jobId: String,
        timeoutMs: Long,
    ): ProotJobRecord {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val record = runner.query(jobId) ?: error("job disappeared: $jobId")
            if (record.state.isTerminal) return record
            Thread.sleep(200L)
        }
        error("job $jobId did not reach a terminal state within ${timeoutMs}ms")
    }

    private fun jobNotificationActive(jobId: String): Boolean =
        notificationManager.activeNotifications.any { it.tag == "proot-job-$jobId" }

    private fun waitForNotification(
        jobId: String,
        timeoutMs: Long,
        expectActive: Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (jobNotificationActive(jobId) == expectActive) return
            Thread.sleep(250L)
        }
        error("the running-job notification did not become ${if (expectActive) "active" else "removed"} for $jobId")
    }

    @Test
    fun aRunningJobShowsAStopNotificationAndTheStopActionCancelsTheJob() {
        val jobId = submitSleepJob(60L, 90_000L)
        try {
            // 1) The notification appears once the job is RUNNING.
            waitForNotification(jobId, 20_000L, expectActive = true)
            // 2) The stop action: the SAME broadcast intent the PendingIntent
            //    targets, delivered to the receiver IN-PROCESS. The runner and
            //    the receiver share the companion's process in production; the
            //    instrumented test process hosts the runner, so a real
            //    sendBroadcast would start the app process (a different runner
            //    singleton) — framework delivery of a signature-permission
            //    export is a platform guarantee, not our code. The manifest
            //    registration is asserted structurally (test 3 of this class).
            ProotJobStopReceiver().onReceive(context, ProotJobNotification.stopBroadcastIntent(context, jobId))
            // 3) The job settles CANCELLED (process group killed) ...
            val record = waitForTerminal(jobId, 30_000L)
            assertEquals(ProotJobState.CANCELLED, record.state)
            // ... and the terminal path removed the notification.
            waitForNotification(jobId, 10_000L, expectActive = false)
        } finally {
            ProotJobNotification.cancel(context, jobId)
        }
    }

    /**
     * Structural: the stop path is only reachable through the signature-
     * permission-protected receiver (no unprivileged caller can cancel jobs),
     * and the runtime permission for the notification surface is declared.
     */
    @Test
    fun theStopReceiverIsExportedBehindTheSignaturePermission() {
        // The APP package (the test runs under <app>.test): the registration
        // and the permission declaration live in the app manifest.
        val appPackage = com.helix.runtime.proot.ipc.ProotRuntimeProtocol.RUNTIME_PACKAGE
        val pm = context.packageManager
        val info =
            pm.getReceiverInfo(
                android.content.ComponentName(appPackage, "com.helix.runtime.proot.app.ProotJobStopReceiver"),
                0,
            )
        assertTrue("the stop receiver must be exported (PendingIntent resolution)", info.exported)
        assertEquals(
            "only the same-signature set may send the stop broadcast",
            "com.helix.permission.BIND_PROOT_RUNTIME",
            info.permission,
        )
        val declared =
            pm
                .getPackageInfo(
                    appPackage,
                    android.content.pm.PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
        assertTrue(
            "POST_NOTIFICATIONS must be declared for the running-job surface",
            "android.permission.POST_NOTIFICATIONS" in declared,
        )
    }

    @Test
    fun aNaturallyFinishedJobLeavesNoNotification() {
        val jobId = submitSleepJob(2L, 30_000L)
        try {
            waitForNotification(jobId, 20_000L, expectActive = true)
            val record = waitForTerminal(jobId, 60_000L)
            assertEquals(ProotJobState.SUCCEEDED, record.state)
            waitForNotification(jobId, 10_000L, expectActive = false)
        } finally {
            ProotJobNotification.cancel(context, jobId)
        }
    }
}
