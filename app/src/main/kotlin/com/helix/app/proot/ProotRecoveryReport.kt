package com.helix.app.proot

import com.helix.app.R

/** Status of the original job, separate from the interrupted ToolCall or verified output. */
data class ProotRecoveryReport(
    val labelRes: Int,
    val canStop: Boolean = false,
) {
    companion object {
        val Unknown = ProotRecoveryReport(R.string.proot_recovery_unknown)
    }
}
