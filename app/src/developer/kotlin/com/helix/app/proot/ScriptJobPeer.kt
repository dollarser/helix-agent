package com.helix.app.proot

import android.content.Context
import android.os.Process
import com.helix.runtime.proot.app.ProotJobStore
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.core.LinuxProcessStat
import java.io.File

/** Read-only witness of the original unreaped job process and its current process tree. */
internal object ScriptJobPeer {
    fun active(
        context: Context,
        job: String,
    ): Boolean = ProotJobStore(ProotRuntimeInstaller.runtimeRoot(context)).load(job)?.state?.isTerminal == false

    @Suppress("ReturnCount") // Kernel identity boundaries refuse before callback admission.
    fun accepts(
        context: Context,
        job: String,
        pid: Int,
        uid: Int,
    ): Boolean {
        if (uid != Process.myUid() || !active(context, job)) return false
        val directory = File(ProotRuntimeInstaller.runtimeRoot(context), "jobs/$job")
        val witness =
            File(directory, "proc.txt").readLines().associate {
                it.substringBefore('=') to it.substringAfter('=')
            }
        val original = witness.getValue("pid").toInt()
        val root = stat(original)
        if (root.startTicks != witness.getValue("startTicks").toLong()) return false
        var current = stat(pid)
        val seen = mutableSetOf<Int>()
        while (seen.add(current.pid)) {
            if (current.pid == original) return true
            if (current.parent <= 1 || current.startTicks < root.startTicks) return false
            current = stat(current.parent)
        }
        return false
    }

    private fun stat(pid: Int) =
        LinuxProcessStat.parse(File("/proc/$pid/stat").readText()).also {
            check(it.pid == pid)
        }
}
