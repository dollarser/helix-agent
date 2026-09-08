package com.helix.app.chat

import com.helix.core.agent.CallTokenAccount
import com.helix.core.model.ModelCallId
import com.helix.core.model.ModelRequest

/** Shared accounting for the Turn and its optional Goal; the same call must have the same charge. */
internal object ModelCallUsage {
    fun account(
        callId: String,
        request: ModelRequest,
        stream: ModelStreamState,
    ): CallTokenAccount =
        CallTokenAccount(
            callId = ModelCallId(callId),
            requestBytes = TurnBudgetTracker.requestSizeBytes(request),
            responseBytes =
                stream.text
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong(),
            inputTokens = stream.inputTokens,
            outputTokens = stream.outputTokens,
        )

    fun total(account: CallTokenAccount): Long =
        if (account.effectiveInput > Long.MAX_VALUE - account.effectiveOutput) {
            Long.MAX_VALUE
        } else {
            account.effectiveInput + account.effectiveOutput
        }
}
