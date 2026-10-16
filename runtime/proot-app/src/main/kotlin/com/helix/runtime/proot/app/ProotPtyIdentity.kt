package com.helix.runtime.proot.app

import com.helix.runtime.proot.core.PtyProcessIdentity
import java.io.File
import java.io.FileNotFoundException

/**
 * A non-debuggable Runtime forks a non-dumpable child. Android's hidepid procfs can hide
 * that child until exec changes its dumpability. Wait for this original, unreaped child;
 * never relaunch, change dumpability or treat missing /proc data as an exit receipt.
 */
internal fun awaitPtyProcessIdentity(
    pid: Int,
    exitObserved: () -> Boolean,
    readStat: (Int) -> String = { File("/proc/$it/stat").readText() },
    nanoTime: () -> Long = System::nanoTime,
    pause: (Long) -> Unit = Thread::sleep,
): PtyProcessIdentity {
    val started = nanoTime()
    while (true) {
        check(!exitObserved()) { "PTY exited before its process identity became visible" }
        try {
            val stat = readStat(pid)
            check(stat.length <= 4096 && stat.startsWith("$pid ("))
            val fields = stat.substringAfterLast(") ").trim().split(' ')
            return PtyProcessIdentity(pid, fields[19].toLong())
        } catch (failure: FileNotFoundException) {
            if (nanoTime() - started >= IDENTITY_WAIT_NANOS) throw failure
        }
        pause(IDENTITY_POLL_MS)
    }
}

private const val IDENTITY_WAIT_NANOS = 5_000_000_000L
private const val IDENTITY_POLL_MS = 10L
