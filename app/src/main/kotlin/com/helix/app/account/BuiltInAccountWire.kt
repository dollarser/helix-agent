package com.helix.app.account

import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse

/** Separate fixed endpoints retain the existing bundled provider identity and permission pipeline. */
class BuiltInAccountWire(
    private val account: AccountSession?,
    private val delegate: WireClient =
        com.helix.provider.api.wire.OkHttpWireClient(
            followRedirects = false,
        ),
) : WireClient {
    private val generation = account?.wire(BuiltInModel.GENERATION_URL, delegate)
    private val catalog = account?.wire(BuiltInModel.CATALOG_URL, delegate)

    fun newInvocation() = BuiltInAccountWire(account, delegate)

    override suspend fun open(request: WireRequest): WireResponse {
        require(
            (request.method == "POST" && request.url == BuiltInModel.GENERATION_URL) ||
                (request.method == "GET" && request.url == BuiltInModel.CATALOG_URL && request.body == null),
        )
        if (account == null) throw java.io.IOException("Account sign-in required")
        val wire = if (request.method == "POST") generation else catalog
        return requireNotNull(wire).open(request)
    }
}
