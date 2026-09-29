package com.helix.tools.framework

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** No queue: saturation proves no submission. An interrupt-ignoring worker occupies its slot until it exits. */
internal object ToolExecutionPools {
    val activity = ToolExecutionActivity()
    val ordinary = bounded(32, "tool-executor")
    val control = bounded(4, "tool-control")

    fun bounded(
        limit: Int,
        name: String,
    ): ThreadPoolExecutor =
        ThreadPoolExecutor(
            0,
            limit,
            60,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { action -> Thread(action, name).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
}
