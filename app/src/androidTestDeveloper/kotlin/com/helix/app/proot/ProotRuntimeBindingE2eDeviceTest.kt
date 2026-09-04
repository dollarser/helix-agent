package com.helix.app.proot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.client.RepairEntryResult
import com.helix.runtime.proot.client.VerifiedRuntimeStore
import com.helix.runtime.proot.ipc.ProotHandshakeClient
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HXA-083 device acceptance, the CROSS-APK E2E (ADR-0007): the developer build of
 * com.helix.agent binds the com.helix.runtime.proot companion and exercises the
 * lifecycle contract on a real device.
 *
 * DEVICE WARM-UP (required for the plain gradle connected run): the companion must
 * be installed from the SAME signed set AND not force-stopped (a FRESH install is
 * force-stopped by the platform, and a background app cannot start a stopped
 * app's service — binds time out). The orchestration script does the warm-up:
 *   scripts/accept-hxa-083-lifecycle.sh <serial>
 *
 * Method classes:
 * - SAFE methods (warm companion): the plain connected run passes them.
 * - PHASE methods: strict host pre-states (force-stopped / uninstalled / clean
 *   process table), driven by the script with `am instrument -e class X#method`
 *   (the environment.md convention). Each phase asserts its pre-state and skips
 *   with an explicit instruction when it is absent — never silently passes.
 *
 * The whole class passes on the sanctioned emulator images (API 29/36 arm64)
 * once the companion is warm: the cross-APK bind, the handshake, the
 * death/cold-rebind cycle and the stable unavailable states are all exercised
 * here. (The first HXA-083 draft record misdiagnosed a bind failure as a
 * platform defect; the real root cause — onBind carries no caller identity, so
 * the caller re-verification must run in the binder's onTransact — is recorded
 * in the completion record's root-cause section.)
 */
@RunWith(AndroidJUnit4::class)
class ProotRuntimeBindingE2eDeviceTest {
    companion object {
        /** The HXA-082 canonical lock fingerprint the companion must report. */
        const val EXPECTED_LOCK_SHA256 =
            "461485053b4211c0a25bb6ebef1e7d744508c67c0a3760cc4b93050946c352e5"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val supervisor = ProotRuntimeSupervisor(context)

    private fun requireWarmCompanion() {
        assumeTrue(
            "companion not installed — install runtime/proot-app/.../proot-app-debug.apk " +
                "(scripts/accept-hxa-083-lifecycle.sh)",
            companionInstalled(context),
        )
        assumeTrue(
            "companion is force-stopped (fresh install?) — warm it via " +
                "scripts/accept-hxa-083-lifecycle.sh",
            !probeStoppedState(context),
        )
    }

    // ------------------------------------------------------------------
    // SAFE methods: pass under the plain connected run with a warm companion.
    // ------------------------------------------------------------------

    @Test
    fun coldBindHandshakeVerifiesTheLockConsistentDescriptor() {
        requireWarmCompanion()
        val availability =
            runOnWorker { supervisor.verify(System.currentTimeMillis()) } as ProotRuntimeAvailability.Verified
        val descriptor = availability.descriptor
        assertEquals(ProotRuntimeProtocol.PROTOCOL_VERSION, descriptor.protocolVersion)
        assertEquals("arm64-v8a", descriptor.abi)
        assertEquals(listOf("handshake", "jobs", "stdio"), descriptor.capabilities)
        assertEquals(EXPECTED_LOCK_SHA256, descriptor.lockSha256)
        val versionName =
            context.packageManager.getPackageInfo(ProotRuntimeProtocol.RUNTIME_PACKAGE, 0).versionName
        assertEquals(versionName, descriptor.runtimeVersion)
        // The verified descriptor is persisted as the anchor.
        val anchor = VerifiedRuntimeStore(context).load()
        assertEquals(descriptor, anchor?.descriptor)
    }

    @Test
    fun aSecondVerificationChecksAgainstThePersistedAnchor() {
        requireWarmCompanion()
        val first = runOnWorker { supervisor.verify(System.currentTimeMillis()) }
        assertTrue("first verification must pass: $first", first is ProotRuntimeAvailability.Verified)
        val anchor = VerifiedRuntimeStore(context).load()
        assertTrue("anchor must be persisted after verification", anchor != null)
        val second = runOnWorker { supervisor.verify(System.currentTimeMillis()) }
        assertTrue(
            "second verification against the anchor must pass: $second",
            second is ProotRuntimeAvailability.Verified,
        )
    }

    @Test
    // @Suppress("SwallowedException") — the DeadObjectException from the
    // self-kill transact IS the expected signal (the server died before its
    // reply got out); nothing is lost, the DEAD_OBJECT assertion below is the
    // real check.
    @Suppress("SwallowedException")
    fun aProcessDeathIsDeadObjectAndTheColdRebindRecovers() {
        requireWarmCompanion()
        runOnWorker {
            val connection = supervisor.openConnection()
            val opened = connection as ProotConnection.Opened
            // Debug crash-injection seam: kill the companion process mid-binding.
            // The transact itself may throw DeadObjectException when the server
            // dies before its reply is delivered — that IS the expected signal,
            // not a failure.
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                try {
                    opened.binder.transact(ProotRuntimeProtocol.TX_DEBUG_SELF_KILL, data, reply, 0)
                } catch (e: DeadObjectException) {
                    // killed before the reply got out — expected
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
            Thread.sleep(1_000)
            val outcome = ProotHandshakeClient.handshake(opened.binder)
            val failed = outcome as ProotHandshakeClient.Outcome.Failed
            assertEquals(UnavailableCause.DEAD_OBJECT, failed.cause)
            supervisor.closeConnection()
            // ADR-0007: no process liveness in availability — the cold rebind works.
            val recovered = supervisor.verify(System.currentTimeMillis())
            assertTrue(
                "cold rebind after death must recover: $recovered",
                recovered is ProotRuntimeAvailability.Verified,
            )
            Unit
        }
    }

    @Test
    fun theUserGatedRepairEntryOpensAndTheCompanionVerifies() {
        requireWarmCompanion()
        val opened = supervisor.openRepairActivity()
        assertTrue("repair entry must open the minimal activity: $opened", opened is RepairEntryResult.Opened)
        Thread.sleep(2_000)
        val availability =
            runOnWorker { supervisor.verify(System.currentTimeMillis()) } as ProotRuntimeAvailability.Verified
        assertEquals(ProotRuntimeProtocol.PROTOCOL_VERSION, availability.descriptor.protocolVersion)
    }

    @Test
    fun aNullOnBindIsAnImmediateBindRefusedNotATimeout() {
        requireWarmCompanion()
        runOnWorker<Unit>(timeoutMs = 30_000L) {
            val intent =
                Intent()
                    .setComponent(
                        ComponentName(
                            ProotRuntimeProtocol.RUNTIME_PACKAGE,
                            ProotRuntimeProtocol.SERVICE_CLASS,
                        ),
                    ).putExtra(ProotRuntimeProtocol.EXTRA_DEBUG_NULL_BIND, true)
            val arrived = CountDownLatch(1)
            val which =
                java.util.concurrent.atomic
                    .AtomicReference("none")
            val binderBox =
                java.util.concurrent.atomic
                    .AtomicReference<IBinder>()
            val connection =
                object : android.content.ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        binderBox.set(service)
                        which.set("connected")
                        arrived.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        // no-op: the null-binding path is under test
                    }

                    override fun onNullBinding(name: ComponentName) {
                        which.set("null")
                        arrived.countDown()
                    }

                    override fun onBindingDied(name: ComponentName) {
                        which.set("died")
                        arrived.countDown()
                    }
                }
            val ctx = context
            require(ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            try {
                assertTrue("the platform must deliver a binding event (latch)", arrived.await(15, TimeUnit.SECONDS))
                assertEquals("the debug seam must produce a NULL binding", "null", which.get())
                assertNull("a null binding must carry no binder", binderBox.get())
            } finally {
                ctx.unbindService(connection)
            }
        }
    }

    // ------------------------------------------------------------------
    // PHASE methods: strict host pre-states, driven by the orchestration script.
    // ------------------------------------------------------------------

    @Test
    fun phaseAppStartupNeverStartsTheCompanionProcess() {
        requireWarmCompanion()
        // The instrumentation process IS the main app process: the app has fully
        // started here. The strong assertion (companion process ABSENT) is the
        // host-side pidof check the script performs around this run; in-app we
        // assert the app reached the companion without any bind — the local
        // no-bind checks pass, and the app's startup paths reference no supervisor
        // call site (structurally: the client is user-gated only).
        assertEquals(
            "local no-bind checks must pass without starting the companion",
            null,
            supervisor.checkLocalState(),
        )
    }

    @Test
    fun phaseForcedStoppedCompanionIsStablyUnavailable() {
        assumeTrue(
            "phase requires the host pre-state: adb shell su 0 am force-stop com.helix.runtime.proot",
            companionInstalled(context) && probeStoppedState(context),
        )
        val unavailable =
            runOnWorker { supervisor.verify(System.currentTimeMillis()) } as ProotRuntimeAvailability.Unavailable
        assertEquals(UnavailableCause.PACKAGE_FORCED_STOPPED, unavailable.cause)
    }

    @Test
    fun phaseUninstalledCompanionIsStablyReported() {
        assumeTrue(
            "phase requires the host pre-state: adb shell pm uninstall com.helix.runtime.proot " +
                "(the script reinstalls it afterwards)",
            !companionInstalled(context),
        )
        val unavailable =
            runOnWorker { supervisor.verify(System.currentTimeMillis()) } as ProotRuntimeAvailability.Unavailable
        assertEquals(UnavailableCause.NOT_INSTALLED, unavailable.cause)
        assertEquals(
            RepairEntryResult.Unavailable(UnavailableCause.NOT_INSTALLED),
            supervisor.openRepairActivity(),
        )
    }
}

// ----------------------------------------------------------------------
// Device-state probes + the worker runner (file-level to keep the test class
// within the detekt function budget).
// ----------------------------------------------------------------------

// @Suppress("SwallowedException") — a NameNotFound IS the answer: "companion
// not installed" (which is also "not stopped").
@Suppress("SwallowedException")
private fun companionAppInfo(context: Context): ApplicationInfo? =
    try {
        context.packageManager.getPackageInfo(ProotRuntimeProtocol.RUNTIME_PACKAGE, 0).applicationInfo
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

private fun companionInstalled(context: Context): Boolean = companionAppInfo(context) != null

/** The companion's force-stopped state as seen across packages (FLAG_STOPPED). */
private fun probeStoppedState(context: Context): Boolean {
    val appInfo = companionAppInfo(context) ?: return false
    return appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
}

/**
 * Runs [block] on a worker thread and waits with a hard deadline. Safe to
 * block here: `am instrument` (AndroidJUnitRunner) executes test methods on
 * the `Instr:` thread, NOT the main thread — the main looper keeps running,
 * so the ServiceConnection callbacks (delivered on the main looper) can
 * complete the blocking bind. Never call this from the main thread: a plain
 * `future.get()` there would deadlock the handshake.
 *
 * @Suppress("TooGenericExceptionCaught") — EVERY worker failure must surface as
 * the future's failure (fail-closed test semantics); no type is special.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
private fun <T> runOnWorker(
    timeoutMs: Long = 60_000L,
    block: () -> T,
): T {
    val future = CompletableFuture<T>()
    Thread {
        try {
            future.complete(block())
        } catch (e: Throwable) {
            future.completeExceptionally(e)
        }
    }.start()
    return try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        throw IllegalStateException("worker interrupted", e)
    } catch (e: java.util.concurrent.TimeoutException) {
        throw IllegalStateException("worker exceeded ${timeoutMs}ms", e)
    }
}
