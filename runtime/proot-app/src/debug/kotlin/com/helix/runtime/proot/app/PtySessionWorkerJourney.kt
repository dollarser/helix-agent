package com.helix.runtime.proot.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.helix.runtime.proot.core.PtyInputConnection
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionStore
import java.io.File
import java.util.UUID

/** Exercises the production worker, including detach while it drains without a reader. */
internal class PtySessionWorkerJourney(
    private val context: Context,
) {
    fun run(launch: () -> ProotPtyProcess) {
        val store = PtySessionStore(File(context.filesDir, "pty-worker-test"))
        val now = SystemClock.elapsedRealtime()
        val origin =
            PtySessionOrigin(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                context.filesDir.canonicalPath,
                UUID.randomUUID().toString(),
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 },
                System.currentTimeMillis(),
                now,
                now + 30_000,
            )
        val session = ProotPtySession(PtySessionRecord(origin), store, launch)
        verifyNoForkOutcomes(store, origin, launch)
        session.start()
        try {
            val first = checkNotNull(session.attach())
            check(session.attach() == null)
            awaitText(session, "helix-native> ")
            check(
                session.write(first, "export HELIX_WORKER=retained\n".toByteArray()) ==
                    PtyInputConnection.Admission.ACCEPTED,
            )
            check(session.detach(first))
            val second = checkNotNull(session.attach())
            check(session.write(first, "exit\n".toByteArray()) == PtyInputConnection.Admission.DETACHED)
            session.resize(31, 93)
            check(
                session.write(second, "printf 'WORKER_%s\\n' \"\$HELIX_WORKER\"; stty size\n".toByteArray()) ==
                    PtyInputConnection.Admission.ACCEPTED,
            )
            awaitText(session, "WORKER_retained")
            awaitText(session, "31 93")
            session.stop(PtySessionRecord.StopReason.USER)
            awaitPty { session.record.phase == PtySessionRecord.Phase.STOPPED }
            check(session.record.stopReason == PtySessionRecord.StopReason.USER)
            check(session.record.stopProof == PtySessionRecord.StopProof.PROCESS_TREE_EXIT)
            check(store.read(origin.sessionId) == session.record)
            val stopped = session.record
            check(store.compareAndSet(stopped, stopped.acknowledge()))
            check(store.removeReconciled(stopped.acknowledge()))
        } finally {
            session.stop(PtySessionRecord.StopReason.USER)
        }
    }

    private fun verifyNoForkOutcomes(
        store: PtySessionStore,
        origin: PtySessionOrigin,
        launch: () -> ProotPtyProcess,
    ) {
        for (reason in listOf(
            PtySessionRecord.StopReason.USER,
            PtySessionRecord.StopReason.START_FAILED,
            PtySessionRecord.StopReason.LEASE_EXPIRED,
        )) {
            val cancelled =
                origin.copy(
                    sessionId = UUID.randomUUID().toString(),
                    deadlineElapsedMs =
                        if (reason == PtySessionRecord.StopReason.LEASE_EXPIRED) {
                            SystemClock.elapsedRealtime() + 1
                        } else {
                            origin.deadlineElapsedMs
                        },
                )
            var launched = false
            val session =
                ProotPtySession(PtySessionRecord(cancelled), store) {
                    launched = true
                    launch()
                }
            if (reason == PtySessionRecord.StopReason.USER) session.stop(reason)
            if (reason == PtySessionRecord.StopReason.LEASE_EXPIRED) Thread.sleep(10)
            session.start(allowLaunch = reason != PtySessionRecord.StopReason.START_FAILED)
            awaitPty { session.record.phase == PtySessionRecord.Phase.STOPPED }
            check(!launched && session.record.stopProof == PtySessionRecord.StopProof.NEVER_STARTED)
            check(session.record.stopReason == reason)
            check(store.compareAndSet(session.record, session.record.acknowledge()))
            check(store.removeReconciled(session.record.acknowledge()))
        }
    }

    private fun awaitText(
        session: ProotPtySession,
        expected: String,
    ) {
        awaitPty {
            session.output
                .read()
                .bytes
                .toString(Charsets.UTF_8)
                .contains(expected)
        }
    }
}
