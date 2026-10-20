package com.helix.app.account

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse

class MemoryAccountTokens(
    var value: AccountTokens? = null,
) : AccountTokenStorage {
    override fun load() = value

    override fun save(tokens: AccountTokens) {
        value = tokens
    }

    override fun clear() {
        value = null
    }
}

fun accountResponse(
    code: Int = 200,
    text: String = "",
): WireResponse =
    WireResponse(
        code,
        emptyMap(),
        object : WireBody {
            override suspend fun bytes() = text.toByteArray()

            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                onChunk(text.toByteArray())
            }

            override fun close() = Unit
        },
    )

fun testAccount() =
    AccountSession(
        MemoryAccountTokens(AccountTokens("fixture-access", "fixture-refresh", 1000000, 2000000, "fixture-user")),
        object : WireClient {
            override suspend fun open(request: WireRequest) =
                accountResponse(
                    200,
                    """
                    {"access_token":"fixture-renewed","refresh_token":"fixture-rotated",
                     "expires_in":600,"refresh_expires_in":1200}
                    """.trimIndent(),
                )
        },
        { 0L },
    )
