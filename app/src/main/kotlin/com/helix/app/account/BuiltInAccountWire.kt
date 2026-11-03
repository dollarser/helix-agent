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
    private val revision = account?.revision

    fun newInvocation() = BuiltInAccountWire(account, delegate)

    override suspend fun open(request: WireRequest): WireResponse {
        require(
            (request.method == "POST" && request.url == BuiltInModel.GENERATION_URL) ||
                (request.method == "GET" && isBuiltInCatalogUrl(request.url) && request.body == null),
        )
        if (account == null) {
            throw com.helix.provider.api.wire.WireRequestStateException(
                com.helix.provider.api.wire.WireRequestStateException.Reason.SIGN_IN_REQUIRED,
            )
        }
        val wire =
            if (request.method ==
                "POST"
            ) {
                generation
            } else {
                account.wire(request.url, delegate, requireNotNull(revision))
            }
        return requireNotNull(wire).open(request)
    }
}

internal fun isBuiltInCatalogUrl(url: String): Boolean {
    if (url == BuiltInModel.CATALOG_URL) return true
    val prefix = BuiltInModel.CATALOG_URL + "&after_id="
    return url.startsWith(prefix) &&
        url.removePrefix(prefix).let {
            it.isNotBlank() && it.length <= 768 && '&' !in it && '#' !in it
        }
}
