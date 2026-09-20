package com.helix.runtime.proot.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.helix.runtime.proot.core.PtyProcessIdentity
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionStore
import java.io.File
import java.util.UUID

/** Fixed failed-exec device journey only; this is not a process-tree shutdown algorithm. */
internal class PtyNativeFailureJournal(
    context: Context,
) {
    private val root = File(context.filesDir, "native-pty-journal")
    private val store = PtySessionStore(root)
    private var current =
        PtySessionRecord(
            PtySessionOrigin(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                context.filesDir.canonicalPath,
                UUID.randomUUID().toString(),
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 },
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                SystemClock.elapsedRealtime() + 60_000,
            ),
        )

    init {
        check(store.compareAndSet(null, current))
        check(PtySessionStore(root).read(current.origin.sessionId) == current)
    }

    fun spawned(pid: Int) {
        // The native wrapper retains this unreaped child, including after a fast exec failure.
        val fields = ptyProcessFields(pid)
        val running = current.started(PtyProcessIdentity(pid, fields[19].toLong()))
        check(store.compareAndSet(current, running))
        current = running
    }

    fun failedExecFinished(status: Int) {
        check(status == 127)
        // This fixed missing executable never executes user code or creates descendants.
        val stopped = current.stoppedTree(status)
        check(store.compareAndSet(current, stopped))
        val reopened = PtySessionStore(root)
        check(reopened.read(stopped.origin.sessionId) == stopped)
        check(!reopened.compareAndSet(current, current.runtimeLost()))
        check(reopened.compareAndSet(stopped, stopped.acknowledge()))
        check(reopened.removeReconciled(stopped.acknowledge()))
        check(reopened.read(stopped.origin.sessionId) == null)
    }
}
