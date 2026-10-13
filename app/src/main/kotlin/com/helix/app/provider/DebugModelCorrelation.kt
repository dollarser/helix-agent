package com.helix.app.provider

import com.helix.core.agent.LocalModelCallContext
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.currentCoroutineContext

/** Explicit Debug form eligibility, not endpoint-based vendor guessing. */
internal fun debugModelCorrelationEnabled(
    debuggable: Boolean,
    config: ProviderConfig,
    customApi: Boolean,
): Boolean =
    debuggable && config.provisioning == ProviderProvisioningKind.USER_CONFIGURED &&
        config.transport is ProviderTransport.Network && customApi

/** App-only Debug decoration: neither model payload nor core execution protocol changes. */
internal class DebugModelCorrelationWireClient(
    private val delegate: WireClient,
) : WireClient {
    override suspend fun open(request: WireRequest): WireResponse {
        val owner = currentCoroutineContext()[LocalModelCallContext]
        if (request.method != "POST" || owner == null) return delegate.open(request)
        val ids = listOf(owner.turnId, owner.modelCallId)
        require(ids.all { ID.matches(it) }) { "Invalid Debug model correlation identity" }
        val names = setOf(ProviderHeaders.TURN_TRACE_HEADER, ProviderHeaders.MODEL_CALL_TRACE_HEADER)
        require(request.headers.keys.none { name -> names.any { it.equals(name, ignoreCase = true) } }) {
            "Debug correlation headers must be host-owned"
        }
        val headers =
            request.headers +
                mapOf(
                    ProviderHeaders.TURN_TRACE_HEADER to owner.turnId,
                    ProviderHeaders.MODEL_CALL_TRACE_HEADER to owner.modelCallId,
                )
        return delegate.open(WireRequest(request.method, request.url, headers, request.body))
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
