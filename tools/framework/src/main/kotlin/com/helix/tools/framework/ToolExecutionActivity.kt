package com.helix.tools.framework

/** Counts actual executor entry/exit, never Future cancellation as exit proof. */
internal class ToolExecutionActivity {
    data class Snapshot(
        val running: Int,
        val abandonedRunning: Int,
    )

    private var running = 0
    private var abandonedRunning = 0

    @Synchronized fun snapshot() = Snapshot(running, abandonedRunning)

    fun attempt() = Attempt()

    inner class Attempt {
        private var started = false
        private var finished = false
        private var abandoned = false

        fun start() =
            synchronized(this@ToolExecutionActivity) {
                started = true
                running++
                if (abandoned) abandonedRunning++
            }

        fun finish() =
            synchronized(this@ToolExecutionActivity) {
                finished = true
                running--
                if (abandoned) abandonedRunning--
            }

        fun abandon() =
            synchronized(this@ToolExecutionActivity) {
                if (!abandoned) {
                    abandoned = true
                    if (started && !finished) abandonedRunning++
                }
            }
    }
}
