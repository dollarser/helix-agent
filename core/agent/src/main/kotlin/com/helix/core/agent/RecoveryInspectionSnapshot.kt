package com.helix.core.agent

/** Original provider binding and control only; recovery never refreshes the task's configuration. */
data class RecoveryInspectionSnapshot(
    val providerId: String,
    val modelId: String,
    val providerSnapshot: String,
    val control: RunControlConfig,
)

object RecoveryInspectionIdentity {
    fun requestId(parentTurnId: String): String = "auto-recovery:$parentTurnId"
}
