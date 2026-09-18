package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotJobState
import java.io.File

/**
 * Orphan sweep (service (re)start, job thread): every PENDING/RUNNING record
 * is terminal ORPHANED. Its process group is killed ONLY when /proc proves
 * the pid still holds the SAME process (matching starttime ticks) — a
 * reused pid is never touched. A sweep failure for one job must not stop
 * the sweep; the /proc scan is nested by nature.
 */
@Suppress(
    "TooGenericExceptionCaught",
    "SwallowedException",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
)
internal fun sweepProotOrphans(
    store: ProotJobStore,
    kill: (Int) -> Unit,
) {
    store.prune(System.currentTimeMillis())
    store.activeJobIds().forEach { jobId ->
        val record = store.load(jobId) ?: return@forEach
        if (record.state.isTerminal) return@forEach
        val procMeta = File(store.jobDir(jobId), "proc.txt")
        if (procMeta.isFile) {
            try {
                val lines = procMeta.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
                val pid = lines["pid"]?.toIntOrNull()
                val startTicks = lines["startTicks"].orEmpty()
                if (pid != null && startTicks.isNotEmpty()) {
                    val statFile = File("/proc/$pid/stat")
                    if (statFile.isFile) {
                        val stat = statFile.readText()
                        val closeParen = stat.lastIndexOf(')')
                        val fields = stat.substring(closeParen + 2).trim().split(" ")
                        if (fields.size > 19 && fields[19] == startTicks) {
                            // The SAME process is still alive from the old incarnation:
                            // it is a true orphan — kill its group, then ORPHANED.
                            kill(pid)
                        }
                    }
                }
            } catch (e: Exception) {
                // meta unreadable: the record still goes ORPHANED; nothing is killed.
            }
        }
        try {
            store.put(
                record.copy(
                    state = ProotJobState.ORPHANED,
                    terminalAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) {
            // one bad record does not stop the sweep
        }
    }
}
