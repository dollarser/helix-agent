package com.helix.app.proot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.client.RepairEntryResult
import com.helix.runtime.proot.client.VerifiedRuntimeStore
import com.helix.runtime.proot.ipc.ProotHandshakeClient
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.RuntimeTargetDescriptor
import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * HXA-087 device acceptance, MAIN-APP side (roadmap §12: 同签名 APK 更新、
 * 完整删除、法律页 — the user-gated flows that cross the uid boundary):
 *
 * - the legal page: `ProotToolModule.openLegalPage()` is the user-click surface;
 *   the companion's `ProotLegalActivity` is exported behind the set's SIGNATURE
 *   permission and launches from this (same-signature) package — the CONTENT
 *   assertions (offline notice / source URLs / embedded license texts /
 *   canonical lock fingerprint) live in the companion-side test
 *   `ProotUpdateLifecycleDeviceTest` where the assets are readable;
 * - the complete removal: `ProotToolModule.removeRuntime()` clears THIS app's
 *   anchor (its own file — the verification claim is void once the runtime is
 *   being removed), degrades the gate to NOT_VERIFIED, and opens the companion's
 *   repair activity carrying the removal consent extra; the main app's own
 *   files (the Workspace side of "不误删 Workspace") are untouched — the
 *   companion's removal scoping is proven companion-side;
 * - the re-baseline: after a companion APK update the embedded lock moves away
 *   from the persisted anchor and verification returns the stable
 *   `LOCK_MISMATCH` (= the 需更新 state). The user's EXPLICIT "重定基线" action
 *   clears the anchor and the next verification re-establishes it against the
 *   new lock — two explicit user actions, never automatic (this test simulates
 *   the "new APK" by persisting an anchor whose lock fingerprint no longer
 *   matches the companion's embedded lock).
 */
@RunWith(AndroidJUnit4::class)
class ProotUpdateLegalE2eDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var supervisor: ProotRuntimeSupervisor
    private var originalAnchor: VerifiedRuntimeStore.Entry? = null

    @Before
    fun setUp() {
        // The instrumentation process is NOT the main app process (no container
        // init): wire the module exactly like the production entry point
        // (registerTools' supervisor/client lines) — what the user-click surfaces
        // under test delegate to.
        ProotToolModule.wireForTest(context)
        supervisor = ProotRuntimeSupervisor(context)
        assumeTrue(
            "companion not installed — install the runtime APK " +
                "(scripts/accept-hxa-083-lifecycle.sh)",
            companionInstalled(context),
        )
        assumeTrue(
            "companion is force-stopped — warm it via scripts/accept-hxa-083-lifecycle.sh",
            !probeStoppedState(context),
        )
        originalAnchor = VerifiedRuntimeStore(context).load()
    }

    @org.junit.After
    fun tearDown() {
        // Restore the anchor the suite started with (no cross-test state drift):
        val store = VerifiedRuntimeStore(context)
        originalAnchor?.let { store.save(it) } ?: store.clear()
    }

    @Test
    fun theLegalPageActivityIsExportedBehindTheSignaturePermission() {
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(
                    ProotRuntimeProtocol.RUNTIME_PACKAGE,
                    ProotRuntimeProtocol.LEGAL_ACTIVITY_CLASS,
                ),
                0,
            )
        assertTrue("the legal page must be exported for the main app's explicit intent", info.exported)
        assertEquals(ProotRuntimeProtocol.PERMISSION_BIND, info.permission)
    }

    @Test
    fun theLegalPageOpensFromTheUserClick() {
        // The user-click surface must OPEN the companion's page (a SecurityException
        // maps to SIGNATURE_MISMATCH, a missing activity to NOT_INSTALLED — a
        // crash here would be the regression, and the stable refusal mapping is
        // the HXA-083 contract):
        val result = ProotToolModule.openLegalPage()
        assertEquals(
            "the legal page must open from the user click: $result",
            RepairEntryResult.Opened,
            result,
        )
    }

    @Test
    fun removeRuntimeClearsTheAnchorDegradesTheGateAndKeepsWorkspaceFiles() {
        // Pre-state: a verified anchor exists (the "verified" state):
        val availability = runOnWorker { supervisor.verify(System.currentTimeMillis()) }
        assumeTrue(
            "a verified runtime is required for the removal pre-state; got: $availability",
            availability is ProotRuntimeAvailability.Verified,
        )
        assertTrue(supervisor.anchorPresent())
        // The Workspace-side marker (main app's own files — must survive EVERY
        // step of the removal flow):
        val marker = File(context.filesDir, "workspace-survives-" + System.nanoTime())
        marker.writeText("workspace-data")
        // The user-click removal:
        val result = ProotToolModule.removeRuntime()
        assertEquals(
            "removal entry must open the companion: $result",
            RepairEntryResult.Opened,
            result,
        )
        // THIS app's anchor is cleared (its own file; no cross-uid access needed):
        assertFalse("the anchor must be cleared by the removal flow", supervisor.anchorPresent())
        // The gate degrades to NOT_VERIFIED (the anchor is the verification claim):
        assertEquals(LinuxRuntimeGate.NOT_VERIFIED, ProotToolModule.availabilityGate())
        // The main app's own data is untouched:
        assertTrue(
            "Workspace-side marker must survive",
            marker.exists() && marker.readText() == "workspace-data",
        )
        marker.delete()
    }

    @Test
    fun rebaselineAfterALockMismatchIsExplicitAndReestablishesTheAnchor() {
        // The companion's CURRENT embedded-lock descriptor (one cold bind):
        val realDescriptor = latestDescriptor()
        // Simulate the post-update state: an anchor whose lock fingerprint no
        // longer matches (a REAL same-signature APK update produces exactly
        // this drift):
        val store = VerifiedRuntimeStore(context)
        store.save(
            VerifiedRuntimeStore.Entry(
                realDescriptor.copy(lockSha256 = "f".repeat(64)),
                System.currentTimeMillis(),
            ),
        )
        // Verification now fails with the STABLE 需更新 cause:
        val mismatched = runOnWorker { supervisor.verify(System.currentTimeMillis()) }
        assertTrue(
            "expected Unavailable (mismatched anchor), got: $mismatched",
            mismatched is ProotRuntimeAvailability.Unavailable,
        )
        assertEquals(
            UnavailableCause.LOCK_MISMATCH,
            (mismatched as ProotRuntimeAvailability.Unavailable).cause,
        )
        // The EXPLICIT re-baseline clears the anchor:
        assertTrue("re-baseline must report a cleared anchor", ProotToolModule.rebaseline())
        assertFalse(supervisor.anchorPresent())
        // The NEXT verification re-establishes the anchor against the real lock:
        val reverified = runOnWorker { supervisor.verify(System.currentTimeMillis()) }
        assertTrue(
            "re-verification must pass: $reverified",
            reverified is ProotRuntimeAvailability.Verified,
        )
        assertEquals(realDescriptor, store.load()?.descriptor)
    }

    /** The companion's current embedded-lock descriptor (one cold bind + handshake). */
    private fun latestDescriptor(): RuntimeTargetDescriptor =
        runOnWorker {
            val arrived = CountDownLatch(1)
            val which = AtomicReference("none")
            val binderBox = AtomicReference<IBinder>()
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
                        // no-op
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
            require(
                context.bindService(
                    Intent().setComponent(
                        ComponentName(ProotRuntimeProtocol.RUNTIME_PACKAGE, ProotRuntimeProtocol.SERVICE_CLASS),
                    ),
                    connection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
            try {
                assertTrue("the companion must deliver a binding event", arrived.await(60, TimeUnit.SECONDS))
                assertEquals("connected", which.get())
                val outcome = ProotHandshakeClient.handshake(binderBox.get()!!)
                when (outcome) {
                    is ProotHandshakeClient.Outcome.Descriptor -> {
                        outcome.descriptor
                    }

                    is ProotHandshakeClient.Outcome.Failed -> {
                        error("handshake failed: ${outcome.cause}")
                    }
                }
            } finally {
                runCatching { context.unbindService(connection) }
            }
        } as RuntimeTargetDescriptor

    // The worker re-throws EVERY exception type into the future (completeExceptionally):
    // the catch-all is the transport, not a swallow — the caller sees the original.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun runOnWorker(block: () -> Any?): Any? {
        val future = CompletableFuture<Any?>()
        Thread {
            try {
                future.complete(block())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }.start()
        return future.get(180, TimeUnit.SECONDS)
    }

    // The NameNotFoundException is the expected "not installed" answer (assume-skip
    // precondition); the specific-catch form still trips detekt's swallow rule.
    @Suppress("SwallowedException")
    private fun companionInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(ProotRuntimeProtocol.RUNTIME_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun probeStoppedState(context: Context): Boolean {
        val appInfo =
            runCatching {
                context.packageManager.getApplicationInfo(
                    ProotRuntimeProtocol.RUNTIME_PACKAGE,
                    0,
                )
            }.getOrNull()
                ?: return false
        return appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
    }
}
