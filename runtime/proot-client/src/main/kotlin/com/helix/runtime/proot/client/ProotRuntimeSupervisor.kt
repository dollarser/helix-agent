package com.helix.runtime.proot.client

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.helix.runtime.proot.core.RuntimeInstallation
import com.helix.runtime.proot.core.RuntimeInstallationState
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.ipc.ProotHandshakeClient
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.UnavailableCause
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** User-initiated connections to the bundled private process. Passive installation queries never bind. */
@Suppress("TooManyFunctions") // Installation queries and bounded private-process connection lifecycle.
class ProotRuntimeSupervisor(
    context: Context,
    private val probe: ProotRuntimeProbe = PackageManagerProotRuntimeProbe(context.applicationContext),
) {
    companion object {
        /** The baseline asset ABI; the companion ships arm64-v8a only (the schema allows x86_64 later). */
        const val EXPECTED_RUNTIME_ABI = "arm64-v8a"
    }

    private val context = context.applicationContext
    private val localState = ProotLocalStateCheck(probe)
    private var activeConnection: ServiceConnection? = null

    /**
     * Local (no-bind) pre-checks in priority order: installed -> force-stopped ->
     * enabled -> same-signature set. Returns the failing stable cause, or null
     * when the process may be worth starting.
     */
    fun checkLocalState(): UnavailableCause? = localState.check()

    /** The active installation is authoritative; an old verification receipt cannot make it ready. */
    fun installation(): RuntimeInstallation =
        runCatching {
            val bundled =
                context.assets
                    .open("runtime/runtime-lock.json")
                    .bufferedReader()
                    .use { it.readText() }
            RuntimeInstallation.inspect(File(context.filesDir, "runtime"), RuntimeLockCodec.parse(bundled))
        }.getOrElse { RuntimeInstallation(RuntimeInstallationState.DAMAGED, diagnostic = it.message) }

    fun installationReady(): Boolean = installation().state == RuntimeInstallationState.PREPARED

    /**
     * Cold-binds the companion with a deadline. On success the binding stays open
     * until [closeConnection]; on refusal no process is left running. Never throws:
     * every failure path is a stable [ProotConnection.Refused] cause.
     */
    @Suppress("ReturnCount") // one return per distinct local-refuse / refused / opened outcome
    @Synchronized
    fun openConnection(deadlineMs: Long = ProotRuntimeProtocol.HANDSHAKE_DEADLINE_MS): ProotConnection {
        if (activeConnection != null) return ProotConnection.Refused(UnavailableCause.BIND_REFUSED)
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

                // API 26+: a dead binding releases the waiter. Missing callbacks are
                // independently bounded by the connection deadline.
                override fun onBindingDied(name: ComponentName) {
                    binderRef.set(null)
                    latch.countDown()
                }
            }
        val intent =
            Intent().setComponent(
                ComponentName(context.packageName, ProotRuntimeProtocol.SERVICE_CLASS),
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
            } catch (_: RuntimeException) {
                unbindQuietly(connection)
                return ProotConnection.Refused(UnavailableCause.BIND_REFUSED)
            }
        if (!accepted) {
            unbindQuietly(connection)
            return ProotConnection.Refused(UnavailableCause.BIND_REFUSED)
        }
        val completed =
            try {
                latch.await(deadlineMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        val binder = binderRef.get()?.takeIf { it.isBinderAlive }
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
    @Synchronized
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

    /** Explicit connection check, bound to the actual installed copy and the current bundled version. */
    fun verify(nowEpochMs: Long): ProotRuntimeAvailability {
        val installed = installation()
        if (installed.state != RuntimeInstallationState.PREPARED) {
            val cause =
                if (installed.state == RuntimeInstallationState.UPDATE_REQUIRED) {
                    UnavailableCause.LOCK_MISMATCH
                } else {
                    UnavailableCause.INSTALLATION_NOT_READY
                }
            return ProotRuntimeAvailability.Unavailable(cause)
        }
        val manifest = requireNotNull(installed.manifest)
        val result =
            withConnection(ProotRuntimeProtocol.HANDSHAKE_DEADLINE_MS) { binder ->
                when (val outcome = ProotHandshakeClient.handshake(binder)) {
                    is ProotHandshakeClient.Outcome.Descriptor -> {
                        val cause =
                            ProotHandshakeClient.check(
                                outcome.descriptor,
                                ProotRuntimeProtocol.PROTOCOL_VERSION,
                                EXPECTED_RUNTIME_ABI,
                                manifest.lockSha256,
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
                result.value
            }

            is ProotConnectionResult.Refused -> {
                ProotRuntimeAvailability.Unavailable(result.cause)
            }
        }
    }

    /** Open maintenance without mutating installation or readiness. */
    fun openRepairActivity(): RepairEntryResult = openCompanionActivity(ProotRuntimeProtocol.REPAIR_ACTIVITY_CLASS)

    fun openLegalActivity(): RepairEntryResult = openCompanionActivity(ProotRuntimeProtocol.LEGAL_ACTIVITY_CLASS)

    // @Suppress("SwallowedException") — launch failures are MAPPED to stable typed
    // refusal results (the HXA-083 contract: a user click must never crash); the
    // exception itself carries no user-safe detail.
    @Suppress("SwallowedException")
    private fun openCompanionActivity(activityClass: String): RepairEntryResult {
        if (!probe.isInstalled()) {
            return RepairEntryResult.Unavailable(UnavailableCause.NOT_INSTALLED)
        }
        val intent =
            Intent()
                .setComponent(
                    ComponentName(context.packageName, activityClass),
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
