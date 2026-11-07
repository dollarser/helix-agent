package com.helix.app.scheduled

import com.helix.core.model.ModelRequest
import com.helix.provider.api.ProviderDescriptor

/** Shared by the loop and scheduled host; registration does not depend on a conversation facade. */
class ScheduledModelRequestGuard {
    @Volatile
    private var guard: (suspend (String, String, ProviderDescriptor, ModelRequest) -> Unit)? = null

    @Synchronized
    fun install(guard: suspend (String, String, ProviderDescriptor, ModelRequest) -> Unit) {
        check(this.guard == null) { "Scheduled model guard already installed" }
        this.guard = guard
    }

    suspend fun verify(
        turnId: String,
        callId: String,
        provider: ProviderDescriptor,
        request: ModelRequest,
    ) {
        guard?.invoke(turnId, callId, provider, request)
    }
}
