package com.helix.spike.termlib

import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Copied only by the fixed-source close probe, never compiled against the unpatched AAR. */
class TerminalCloseProbeTest : InstrumentationTestCase() {
    // Reclamation probe: GC is the operation under test, never production cleanup.
    @Suppress("ExplicitGarbageCollectionCall")
    fun testClosedTerminalsReleaseNativeCallbackRoots() {
        val queue = ReferenceQueue<TerminalEmulator>()
        val references = List(20) { createAndClose(queue) }
        val deadline = SystemClock.elapsedRealtime() + 5000
        var reclaimed = 0
        // Do not repeatedly dereference weak refs during GC: that can keep the
        // last observed object live in the test's own stack registers.
        while (reclaimed < references.size && SystemClock.elapsedRealtime() < deadline) {
            System.gc()
            System.runFinalization()
            SystemClock.sleep(50)
            while (queue.poll() != null) reclaimed++
        }
        assertEquals("Closed emulators still strongly retained", references.size, reclaimed)
    }

    private fun createAndClose(queue: ReferenceQueue<TerminalEmulator>): WeakReference<TerminalEmulator> {
        lateinit var terminal: TerminalEmulator
        instrumentation.runOnMainSync {
            terminal = TerminalEmulatorFactory.create()
            terminal.writeInput("close-中文\r\n".toByteArray())
            terminal.close()
            terminal.close()
            try {
                terminal.writeInput("late".toByteArray())
                fail("Closed terminal accepted native input")
            } catch (_: IllegalStateException) {
                // Exact expected rejection from the released native handle.
            }
        }
        return WeakReference(terminal, queue)
    }
}
