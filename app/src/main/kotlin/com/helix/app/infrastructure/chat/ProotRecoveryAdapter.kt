package com.helix.app.infrastructure.chat

import com.helix.app.proot.ProotToolModule
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.recovery.ProotRecoveryPort
import com.helix.feature.conversation.presentation.ProotRecoveredOutput
import com.helix.feature.conversation.presentation.ProotRecoveryReport

/** Variant-specific original executor access. Existing binding and authorization checks remain authoritative. */
internal class ProotRecoveryAdapter(
    private val storage: HelixStorage,
) : ProotRecoveryPort {
    override val available: Boolean get() = ProotToolModule.AVAILABLE

    override fun inspect(
        turnId: String,
        callId: String,
        stop: Boolean,
    ): ProotRecoveryReport = ProotToolModule.inspectInterruptedJob(storage, turnId, callId, stop)

    override fun collect(
        turnId: String,
        callId: String,
        localOnly: Boolean,
    ): ProotRecoveredOutput? = ProotToolModule.recoverInterruptedResult(storage, turnId, callId, localOnly)
}
