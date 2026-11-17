package com.helix.app.account

import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.readHttpFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** No disk cache, model API key, cookies, or automatic replay after uncertain transport failure. */
internal class AccountNoticesClient(
    private val account: AccountSession,
    private val transport: WireClient = OkHttpWireClient(followRedirects = false, maxBodyBytes = MAX_BODY_BYTES),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountNoticesApi {
    override suspend fun list(
        page: Int,
        state: AccountNoticeState,
    ): AccountNoticesPage {
        require(page in 1..1000)
        val document = request("/account/notices?page=$page&state=${state.wireValue}")
        return AccountNoticeParser.page(document, page)
    }

    override suspend fun unreadCount(): Int = AccountNoticeParser.unread(request("/account/notices/unread-count"))

    /** Explicit user-read intent only. An unknown outcome may be retried because the server is idempotent. */
    override suspend fun markRead(ids: List<String>): Int {
        require(ids.size in 1..100 && ids.toSet().size == ids.size && ids.all { NOTICE_ID.matches(it) })
        val body = buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }.toString().toByteArray()
        val document = request("/account/notices/read", body)
        if (document["ok"] != JsonPrimitive(true)) throw AccountNoticesFormatFailure()
        return AccountNoticeParser.unread(document)
    }

    private suspend fun request(
        path: String,
        body: ByteArray? = null,
        revision: Long = account.revision,
    ): JsonObject =
        withContext(dispatcher) {
            val endpoint = OfficialServices.ACCOUNT_BASE_URL + path
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                val headers =
                    buildMap {
                        put("Accept", "application/json")
                        put("Cache-Control", "no-store")
                        if (body != null) put("Content-Type", "application/json")
                    }
                val response =
                    account.wire(endpoint, transport, revision).open(
                        WireRequest(if (body == null) "GET" else "POST", endpoint, headers, body),
                    )
                try {
                    if (response.status !in 200..299) throw AccountRequestFailure(readHttpFailure(response))
                    val bytes = response.body.bytes()
                    if (bytes.size > MAX_BODY_BYTES) throw AccountNoticesFormatFailure()
                    AccountNoticeParser.document(bytes)
                } finally {
                    response.body.close()
                }
            } ?: throw java.net.SocketTimeoutException("Account notices response timeout")
        }

    private companion object {
        const val MAX_BODY_BYTES = 2097152L
        const val REQUEST_TIMEOUT_MS = 30000L
    }
}

internal val NOTICE_ID = Regex("[0-9a-fA-F]{48}")

internal class AccountNoticesFormatFailure : IOException("Account notices response has an unsupported format")

/** Only this exact generated query shape is admitted to the authenticated account boundary. */
internal fun isAccountNoticesUrl(url: String): Boolean =
    url.startsWith("${OfficialServices.ACCOUNT_BASE_URL}/account/notices?") &&
        Regex("page=([1-9][0-9]{0,2}|1000)&state=(all|unread)").matches(url.substringAfter('?'))
