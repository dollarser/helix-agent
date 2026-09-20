package com.helix.runtime.proot.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionStore
import com.helix.runtime.proot.ipc.PtySessionKey
import com.helix.runtime.proot.ipc.PtySessionProtocol
import com.helix.runtime.proot.ipc.PtySessionReply
import java.io.File
import java.util.UUID

/** One live manual execution in the private Runtime; observation cannot create a shell. */
internal class ProotTerminalHost(
    private val context: Context,
) {
    private val generation = UUID.randomUUID().toString()
    private val store = PtySessionStore(File(context.filesDir, "terminal-sessions"))
    private val runner = ProotJobRunner.get(context)
    private var current: ProotPtySession? = null
    private var attached = false
    private var lastActivity = SystemClock.elapsedRealtime()

    @Synchronized
    // Pre-launch refusals differ from an uncertain submitted execution.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun start(
        key: PtySessionKey,
        workspace: String,
        leaseMs: Long,
        foreground: () -> Boolean,
    ): PtySessionReply {
        require(leaseMs in 1000..PtySessionProtocol.MAX_LEASE_MS)
        val existing = store.read(key.sessionId)
        if (existing != null) {
            require(key.matches(existing) && existing.origin.workspace == workspace)
            return PtySessionReply(query(key), outcome = "DUPLICATE")
        }
        val records = store.records()
        if (current != null || records.any { !it.reconciled }) return PtySessionReply(null, outcome = "START_REFUSED")
        if (records.size >= PtySessionStore.MAX_ENTRIES) {
            check(store.removeReconciled(records.minBy { it.origin.createdAtEpochMs }))
        }
        val launch =
            try {
                ProotTerminalLaunch(context, workspace, key.sessionId)
            } catch (failure: Exception) {
                android.util.Log.w("ManualPty", "Manual terminal preparation failed", failure)
                return PtySessionReply(null, outcome = "START_REFUSED")
            }
        if (!runner.reserveDetached(reservation(key))) return PtySessionReply(null, outcome = "START_REFUSED")
        val now = SystemClock.elapsedRealtime()
        val initial =
            PtySessionRecord(
                PtySessionOrigin(
                    key.sessionId,
                    key.generation,
                    key.executionId,
                    launch.workspace.path,
                    generation,
                    bootCount(),
                    System.currentTimeMillis(),
                    now,
                    Math.addExact(now, leaseMs),
                ),
            )
        val session =
            try {
                ProotPtySession(initial, store, launch::spawn)
            } catch (failure: Exception) {
                runner.releaseDetached(reservation(key))
                throw failure
            }
        current = session
        attached = false
        lastActivity = now
        // A failed foreground promotion is settled by the live worker without calling the launcher.
        val promoted =
            try {
                foreground()
            } catch (failure: Exception) {
                android.util.Log.w("ManualPty", "Manual terminal promotion failed", failure)
                false
            }
        session.start(allowLaunch = promoted)
        return PtySessionReply(session.record)
    }

    @Synchronized
    fun query(key: PtySessionKey): PtySessionRecord? {
        val live = current?.takeIf { key.matches(it.record) }
        if (live != null) return live.record
        return store.read(key.sessionId)?.let { old ->
            require(key.matches(old))
            var next = old.runtimeLost()
            val boot = bootCount()
            val originalBoot = next.origin.bootCount
            val rebooted = originalBoot != null && boot != null && boot > originalBoot
            if (next.stopProof == null && rebooted) {
                next = next.afterReboot(boot)
            }
            if (next != old) check(store.compareAndSet(old, next))
            next
        }
    }

    @Synchronized
    fun live(key: PtySessionKey): ProotPtySession = checkNotNull(current?.takeIf { key.matches(it.record) })

    @Synchronized
    fun activity(attached: Boolean? = null) {
        if (attached != null) this.attached = attached
        lastActivity = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun stop(key: PtySessionKey): PtySessionRecord? {
        current?.takeIf { key.matches(it.record) }?.stop(PtySessionRecord.StopReason.USER)
        return query(key)
    }

    @Synchronized
    fun acknowledge(key: PtySessionKey): PtySessionRecord {
        val record = checkNotNull(query(key))
        check(record.stopProof != null)
        val acknowledged = record.acknowledge()
        if (record != acknowledged) check(store.compareAndSet(record, acknowledged))
        if (current?.record?.origin == record.origin) current = null
        runner.releaseDetached(reservation(key))
        // Retain the acknowledgement so a lost reply cannot turn an idempotent ACK into NOT_FOUND.
        return acknowledged
    }

    @Synchronized
    fun tick(): Boolean {
        val session = current ?: return false
        return when {
            session.record.stopProof != null -> {
                val origin = session.record.origin
                runner.releaseDetached(
                    reservation(PtySessionKey(origin.sessionId, origin.generation, origin.executionId)),
                )
                false
            }

            session.record.phase == PtySessionRecord.Phase.UNKNOWN -> {
                false
            }

            else -> {
                if (!attached && SystemClock.elapsedRealtime() - lastActivity >= PtySessionProtocol.IDLE_MS) {
                    session.stop(PtySessionRecord.StopReason.IDLE)
                }
                true
            }
        }
    }

    @Synchronized
    fun destroy() {
        current?.stop(PtySessionRecord.StopReason.USER)
    }

    private fun bootCount(): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf {
            it >=
                0
        }

    private fun reservation(key: PtySessionKey): String = "pty-${key.sessionId}"
}
