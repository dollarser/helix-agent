package com.helix.runtime.proot.client

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.helix.runtime.proot.ipc.ProotHandshakeClient
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.UnavailableCause
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The main app's ONLY path to the PRoot companion (HXA-083; ADR-0007).
 *
 * Guarantees implemented here:
 * - The companion process is started ONLY by [verify] (the user's zero-Job
 *   "验证 Runtime" click) or by the real-job binding HXA-084 will add on top of
 *   [withConnection]. Nothing in the main app starts it at startup, when the user
 *   switches Advanced, or during a passive Registry refresh.
 * - Every use is a cold bind: explicit [ComponentName], signature permission,
 *   `BIND_AUTO_CREATE`; the binding is closed as soon as the use completes, so an
 *   idle companion process is reclaimable by the system.
 * - Availability is a STABLE state ([ProotRuntimeAvailability]) — no caller may
 *   fall back to the main app shell on any failure.
 * - All methods block with a deadline; callers must invoke them off the main
 *   thread. A single active binding at a time (jobs are serialized in HXA-084).
 */
class ProotRuntimeSupervisor(
    private val context: Context,
    private val probe: ProotRuntimeProbe = PackageManagerProotRuntimeProbe(context),
    private val store: VerifiedRuntimeStore = VerifiedRuntimeStore(context),
) {
    companion object {
        /** The baseline asset ABI; the companion ships arm64-v8a only (the schema allows x86_64 later). */
        const val EXPECTED_RUNTIME_ABI = "arm64-v8a"
    }

    private val localState = ProotLocalStateCheck(probe)
    private var activeConnection: ServiceConnection? = null

    /**
     * Local (no-bind) pre-checks in priority order: installed -> force-stopped ->
     * enabled -> same-signature set. Returns the failing stable cause, or null
     * when the process may be worth starting.
     */
    fun checkLocalState(): UnavailableCause? = localState.check()

    /**
     * Cold-binds the companion with a deadline. On success the binding stays open
     * until [closeConnection]; on refusal no process is left running. Never throws:
     * every failure path is a stable [ProotConnection.Refused] cause.
     */
    @Suppress("ReturnCount") // one return per distinct local-refuse / refused / opened outcome
    fun openConnection(deadlineMs: Long = ProotRuntimeProtocol.HANDSHAKE_DEADLINE_MS): ProotConnection {
        checkLocalState()?.let { return ProotConnection.Refused(it) }
        val binderRef = AtomicReference<IBinder>()
        val latch = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    binderRef.set(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    binderRef.set(null)
                    latch.countDown()
                }

                // A null binder (onBind returned null) is a REFUSAL, not a hang:
                // release the latch immediately so the caller gets a stable
                // BIND_REFUSED instead of a fake HANDSHAKE_TIMEOUT.
                override fun onNullBinding(name: ComponentName) {
                    latch.countDown()
                }

                // The bound process died (API 35+ callback form): release the
                // latch too. If a binder was already delivered, the caller gets
                // it and the next transact reports DEAD_OBJECT (honest); if not,
                // this is a plain refusal. On older platforms the callback never
                // fires and a pre-connection death degrades to the deadline
                // timeout — still a stable, honest state.
                override fun onBindingDied(name: ComponentName) {
                    latch.countDown()
                }
            }
        val intent =
            Intent().setComponent(
                ComponentName(ProotRuntimeProtocol.RUNTIME_PACKAGE, ProotRuntimeProtocol.SERVICE_CLASS),
            )

        // A different signing key cannot hold the signature-level bind permission:
        // the platform rejects the attempt with a SecurityException (same stable
        // cause as the repair-entry mapping) or returns false. This method never
        // throws (see KDoc).
        // @Suppress("SwallowedException") — the exception IS the answer: it maps
        // to the stable SIGNATURE_MISMATCH refusal, nothing else to preserve.
        @Suppress("SwallowedException")
        val accepted =
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                unbindQuietly(connection)
                return ProotConnection.Refused(UnavailableCause.SIGNATURE_MISMATCH)
            }
        if (!accepted) {
            unbindQuietly(connection)
            return ProotConnection.Refused(UnavailableCause.BIND_REFUSED)
        }
        val completed = latch.await(deadlineMs, TimeUnit.MILLISECONDS)
        val binder = binderRef.get()
        if (completed && binder != null) {
            activeConnection = connection
            return ProotConnection.Opened(binder)
        }
        unbindQuietly(connection)
        return ProotConnection.Refused(
            if (completed) UnavailableCause.BIND_REFUSED else UnavailableCause.HANDSHAKE_TIMEOUT,
        )
    }

    /** Releases the current binding; the idle companion process becomes reclaimable. */
    fun closeConnection() {
        val connection = activeConnection ?: return
        activeConnection = null
        unbindQuietly(connection)
    }

    /** Opens a cold binding, runs [block] against the live binder, and always closes it. */
    fun <T> withConnection(
        deadlineMs: Long,
        block: (IBinder) -> T,
    ): ProotConnectionResult<T> =
        when (val connection = openConnection(deadlineMs)) {
            is ProotConnection.Opened -> {
                try {
                    ProotConnectionResult.Success(block(connection.binder))
                } finally {
                    closeConnection()
                }
            }

            is ProotConnection.Refused -> {
                ProotConnectionResult.Refused(connection.cause)
            }
        }

    /**
     * The zero-Job user verification (ADR-0007 decision 1). Binds, handshakes,
     * validates the descriptor against the persisted anchor (first verification:
     * structural only), persists a newly verified descriptor as the anchor, and
     * unbinds. The result is a stable [ProotRuntimeAvailability].
     */
    fun verify(nowEpochMs: Long): ProotRuntimeAvailability {
        val anchor = store.load()?.descriptor
        val result =
            withConnection(ProotRuntimeProtocol.HANDSHAKE_DEADLINE_MS) { binder ->
                when (val outcome = ProotHandshakeClient.handshake(binder)) {
                    is ProotHandshakeClient.Outcome.Descriptor -> {
                        val cause =
                            ProotHandshakeClient.check(
                                outcome.descriptor,
                                ProotRuntimeProtocol.PROTOCOL_VERSION,
                                EXPECTED_RUNTIME_ABI,
                                anchor?.lockSha256,
                            )
                        if (cause != null) {
                            ProotRuntimeAvailability.Unavailable(cause)
                        } else {
                            ProotRuntimeAvailability.Verified(outcome.descriptor, nowEpochMs)
                        }
                    }

                    is ProotHandshakeClient.Outcome.Failed -> {
                        ProotRuntimeAvailability.Unavailable(outcome.cause)
                    }
                }
            }
        return when (result) {
            is ProotConnectionResult.Success -> {
                val availability = result.value
                if (availability is ProotRuntimeAvailability.Verified) {
                    persistAnchor(availability)
                }
                availability
            }

            is ProotConnectionResult.Refused -> {
                ProotRuntimeAvailability.Unavailable(result.cause)
            }
        }
    }

    /**
     * Starts the companion's minimal repair Activity via an explicit intent.
     * This method is only ever invoked from a user click (the main app wires no
     * automatic caller); it also lifts a force-stopped state, which is by design
     * the ONLY recovery path for that state (section 6.7.6).
     *
     * Launch failures are mapped to stable typed results: a missing activity is
     * NOT_INSTALLED (the package vanished mid-gesture) and a SecurityException is
     * SIGNATURE_MISMATCH (the permission no longer grants — the app must never
     * crash on a click).
     */
    @Suppress("SwallowedException")
    fun openRepairActivity(): RepairEntryResult {
        if (!probe.isInstalled()) {
            return RepairEntryResult.Unavailable(UnavailableCause.NOT_INSTALLED)
        }
        val intent =
            Intent()
                .setComponent(
                    ComponentName(ProotRuntimeProtocol.RUNTIME_PACKAGE, ProotRuntimeProtocol.REPAIR_ACTIVITY_CLASS),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            RepairEntryResult.Opened
        } catch (e: ActivityNotFoundException) {
            RepairEntryResult.Unavailable(UnavailableCause.NOT_INSTALLED)
        } catch (e: SecurityException) {
            RepairEntryResult.Unavailable(UnavailableCause.SIGNATURE_MISMATCH)
        }
    }

    // @Suppress("SwallowedException") — a failed anchor write degrades to "first
    // verification again" on the next verify(); the wire verification itself
    // already succeeded, so availability must not be downgraded for a store error.
    @Suppress("SwallowedException")
    private fun persistAnchor(availability: ProotRuntimeAvailability.Verified) {
        runCatching {
            store.save(VerifiedRuntimeStore.Entry(availability.descriptor, availability.verifiedAtEpochMs))
        }
    }

    private fun unbindQuietly(connection: ServiceConnection) {
        runCatching { context.unbindService(connection) }
    }
}

/** Outcome of a cold bind attempt: a live binder to use, or a stable refusal cause. */
sealed interface ProotConnection {
    data class Opened(
        val binder: IBinder,
    ) : ProotConnection

    data class Refused(
        val cause: UnavailableCause,
    ) : ProotConnection
}

/** Outcome of a scoped connection use: the block's value, or the refusal cause. */
sealed interface ProotConnectionResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ProotConnectionResult<T>

    data class Refused(
        val cause: UnavailableCause,
    ) : ProotConnectionResult<Nothing>
}

/** Outcome of the user-gated repair entry. */
sealed interface RepairEntryResult {
    data object Opened : RepairEntryResult

    data class Unavailable(
        val cause: UnavailableCause,
    ) : RepairEntryResult
}
