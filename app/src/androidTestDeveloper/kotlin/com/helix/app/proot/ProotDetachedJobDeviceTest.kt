package com.helix.app.proot

import android.os.Parcel
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@Suppress("TooManyFunctions") // Separate device cases for admission, ownership, budget, cancellation and recovery.
class ProotDetachedJobDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val client by lazy { DetachedJobClient(context) }
    private val jobs = mutableListOf<DetachedJobFixture>()

    @Before fun prepare() {
        ensureInstalledRuntime(context)
    }

    @After fun cleanup() {
        jobs.forEach { client.cancel(it.binding) }
    }

    @Test fun foregroundOwnerContinuesAfterHomeAndReturnsVerifiedOutput() {
        val job = fixture("sleep 2; printf DETACHED_OK")
        assertTrue(job.submit(client).accepted)
        shell("input keyevent KEYCODE_HOME")
        val record = client.awaitTerminal(job.binding)
        assertEquals(ProotJobState.SUCCEEDED, record.state)
        val extracted = File(job.directory, "verified")
        assertEquals(record.outputManifestSha256, ZipJobExtractor.extract(job.output, extracted).manifestSha256)
        assertEquals("DETACHED_OK", File(extracted, "stdout.txt").readText())
    }

    @Test fun duplicateDoesNotRestartOrRenewAndForeignBindingCannotObserveOrCancel() {
        val job = fixture("sleep 3; printf ONCE")
        val initial = job.submit(client)
        assertTrue(initial.accepted)
        val repeated = job.submit(client)
        assertEquals(ProotRuntimeProtocol.REPLY_JOB_DUPLICATE, repeated.status)
        assertEquals(initial.record!!.createdAtEpochMs, repeated.record!!.createdAtEpochMs)
        val foreign = job.binding.copy(sessionId = "other-session")
        assertEquals(ProotRuntimeProtocol.REPLY_JOB_REJECTED, client.query(foreign).status)
        assertEquals(ProotRuntimeProtocol.REPLY_JOB_REJECTED, client.cancel(foreign).status)
        assertEquals(ProotJobState.SUCCEEDED, client.awaitTerminal(job.binding).state)
    }

    @Test fun budgetTightensLeaseAndTerminatesTheProcess() {
        val job = fixture("sleep 60")
        val start = android.os.SystemClock.elapsedRealtime()
        assertTrue(job.submit(client, budgetMs = 3_000).accepted)
        assertEquals(ProotJobState.TIMED_OUT, client.awaitTerminal(job.binding).state)
        assertTrue(android.os.SystemClock.elapsedRealtime() - start < 15_000)
    }

    @Test fun exhaustedClientBudgetDoesNotSubmitOrCreateAJob() {
        val job = fixture("printf MUST_NOT_RUN")
        val reply = job.submit(client, budgetMs = 999)
        assertEquals(ProotRuntimeProtocol.REPLY_JOB_REJECTED, reply.status)
        assertEquals("BUDGET_EXHAUSTED_BEFORE_SUBMIT", reply.refusal)
        assertEquals(null, client.query(job.binding).record)
    }

    @Test fun cancellationIsDurableAndIdempotent() {
        val job = fixture("printf BEGIN; sleep 60")
        assertTrue(job.submit(client).accepted)
        assertNotNull(client.cancel(job.binding).record)
        val record = client.awaitTerminal(job.binding)
        assertEquals(ProotJobState.CANCELLED, record.state)
        assertEquals(record.terminalCommit, client.cancel(job.binding).record!!.terminalCommit)
    }

    @Test fun exhaustedBudgetAndSecondLiveJobAreRefused() {
        val exhausted = fixture("printf MUST_NOT_RUN")
        assertFalse(exhausted.submit(client, 999).accepted)
        val first = fixture("sleep 60")
        assertTrue(first.submit(client).accepted)
        val second = fixture("printf MUST_NOT_RUN")
        assertEquals("EXECUTION_BUSY", second.submit(client).refusal)
        client.cancel(first.binding)
        assertEquals(ProotJobState.CANCELLED, client.awaitTerminal(first.binding).state)
    }

    @Test fun platformForegroundDenialDoesNotLaunchAJob() {
        val job = fixture("printf MUST_NOT_RUN")
        shell("appops set ${context.packageName} START_FOREGROUND deny")
        try {
            val result = job.submit(client)
            assertFalse(result.accepted)
            assertEquals("BACKGROUND_UNAVAILABLE", result.refusal)
            assertEquals(null, client.query(job.binding).record)
        } finally {
            shell("appops set ${context.packageName} START_FOREGROUND allow")
        }
    }

    @Test fun runtimeDeathReconcilesWithoutReplay() {
        val job = fixture("sleep 60; printf MUST_NOT_REPLAY")
        assertTrue(job.submit(client).accepted)
        val supervisor = ProotRuntimeSupervisor(context)
        val connection = supervisor.openConnection() as ProotConnection.Opened
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            runCatching { connection.binder.transact(ProotRuntimeProtocol.TX_DEBUG_SELF_KILL, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
            supervisor.closeConnection()
        }
        assertEquals(ProotJobState.ORPHANED, client.awaitTerminal(job.binding).state)
        assertEquals(ProotRuntimeProtocol.REPLY_JOB_DUPLICATE, job.submit(client).status)
    }

    private fun fixture(script: String): DetachedJobFixture = DetachedJobFixture(context, script).also(jobs::add)
}

private fun shell(command: String) =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor
            .AutoCloseInputStream(it)
            .bufferedReader()
            .readText()
    }

private fun DetachedJobClient.awaitTerminal(binding: DetachedJobBinding): ProotJobRecord {
    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        val record = query(binding).record
        if (record?.state?.isTerminal == true) return record
        Thread.sleep(100)
    }
    error("Job did not settle: ${query(binding)}")
}
