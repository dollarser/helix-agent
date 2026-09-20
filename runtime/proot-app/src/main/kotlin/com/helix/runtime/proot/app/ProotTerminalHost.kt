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

/** Up to two live manual executions in the private Runtime; observation cannot create a shell. */
internal class ProotTerminalHost(
    private val context: Context,
) {
    private val generation = UUID.randomUUID().toString()
    private val store = PtySessionStore(File(context.filesDir, "terminal-sessions"))
    private val runner = ProotJobRunner.get(context)
    private val sessions = mutableMapOf<String, ProotPtySession>()
    private val attachedSessions = mutableSetOf<String>()
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
        val activeRecords = records.filter { !it.reconciled }
        if (sessions.size >= MAX_SESSIONS || activeRecords.size >= MAX_SESSIONS) {
            return PtySessionReply(null, outcome = "CAPACITY_EXHAUSTED")
        }
        if (records.size >= PtySessionStore.MAX_ENTRIES) {
            val toRemove = records.filter { it.reconciled }.minByOrNull { it.origin.createdAtEpochMs }
            if (toRemove != null) {
                check(store.removeReconciled(toRemove))
            }
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
        sessions[key.sessionId] = session
        attachedSessions.add(key.sessionId)
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
        val live = sessions[key.sessionId]?.takeIf { key.matches(it.record) }
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
    fun live(key: PtySessionKey): ProotPtySession =
        checkNotNull(sessions[key.sessionId]?.takeIf { key.matches(it.record) })

    @Synchronized
    fun activity(
        sessionId: String? = null,
        attached: Boolean? = null,
    ) {
        if (sessionId != null && attached != null) {
            if (attached) attachedSessions.add(sessionId) else attachedSessions.remove(sessionId)
        }
        lastActivity = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun stop(key: PtySessionKey): PtySessionRecord? {
        sessions[key.sessionId]?.takeIf { key.matches(it.record) }?.stop(PtySessionRecord.StopReason.USER)
        return query(key)
    }

    @Synchronized
    fun acknowledge(key: PtySessionKey): PtySessionRecord {
        val record = checkNotNull(query(key))
        check(record.stopProof != null)
        val acknowledged = record.acknowledge()
        if (record != acknowledged) check(store.compareAndSet(record, acknowledged))
        if (sessions[key.sessionId]?.record?.origin == record.origin) {
            sessions.remove(key.sessionId)
            attachedSessions.remove(key.sessionId)
        }
        runner.releaseDetached(reservation(key))
        // Retain the acknowledgement so a lost reply cannot turn an idempotent ACK into NOT_FOUND.
        return acknowledged
    }

    @Synchronized
    fun tick(): Boolean {
        if (sessions.isEmpty()) return false
        var anyActive = false
        val now = SystemClock.elapsedRealtime()
        for ((id, session) in sessions.toList()) {
            when {
                session.record.stopProof != null -> {
                    val origin = session.record.origin
                    runner.releaseDetached(
                        reservation(PtySessionKey(origin.sessionId, origin.generation, origin.executionId)),
                    )
                }

                session.record.phase == PtySessionRecord.Phase.UNKNOWN -> {
                    // Not counted as active, does not prevent teardown
                }

                else -> {
                    if (!attachedSessions.contains(id) && now - lastActivity >= PtySessionProtocol.IDLE_MS) {
                        session.stop(PtySessionRecord.StopReason.IDLE)
                    } else {
                        anyActive = true
                    }
                }
            }
        }
        return anyActive
    }

    @Synchronized
    fun destroy() {
        sessions.values.forEach { it.stop(PtySessionRecord.StopReason.USER) }
    }

    private fun bootCount(): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf {
            it >=
                0
        }

    private fun reservation(key: PtySessionKey): String = "pty-${key.sessionId}"

    companion object {
        const val MAX_SESSIONS = 2
    }
}
