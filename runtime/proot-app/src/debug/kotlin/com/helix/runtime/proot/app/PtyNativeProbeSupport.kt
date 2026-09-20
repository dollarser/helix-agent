package com.helix.runtime.proot.app

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

internal fun usePtyProcess(
    process: ProotPtyProcess,
    action: (ProotPtyProcess) -> Unit,
) {
    try {
        action(process)
    } finally {
        try {
            if (process.pollExit() == null) {
                process.killInitialGroup()
                awaitPty { process.pollExit() != null }
            }
            process.reap()
        } finally {
            process.closeMaster()
        }
    }
}

internal fun ptyExists(pid: Int): Boolean =
    try {
        Os.kill(pid, 0)
        true
    } catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.ESRCH) throw failure
        false
    }

internal fun awaitPty(condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15000
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < deadline) { "Native PTY deadline exceeded" }
        SystemClock.sleep(5)
    }
}

internal fun ptyProcessFields(pid: Int): List<String> {
    require(pid > 1)
    val stat = File("/proc/$pid/stat").readText()
    check(stat.length <= 4096 && stat.startsWith("$pid ("))
    return stat.substring(stat.lastIndexOf(')') + 2).split(' ').also { check(it.size > 19) }
}
