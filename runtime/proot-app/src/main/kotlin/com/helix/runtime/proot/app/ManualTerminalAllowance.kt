package com.helix.runtime.proot.app

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import com.helix.runtime.proot.core.RuntimeExecutionCapacity

/** New admission only; never kills an existing shell when resources become constrained. */
internal fun manualTerminalAllowance(context: Context): Int {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
    val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
    return if (memory.lowMemory || thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
        1
    } else {
        Runtime.getRuntime().availableProcessors().coerceIn(1, RuntimeExecutionCapacity.MAX_TERMINALS)
    }
}
