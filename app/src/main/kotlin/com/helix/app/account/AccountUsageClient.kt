package com.helix.app.account

import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.readHttpFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** Account-management transport. API-key providers are deliberately not accepted here. */
internal class AccountUsageClient(
    private val account: AccountSession,
    private val transport: WireClient = OkHttpWireClient(followRedirects = false, maxBodyBytes = MAX_BODY_BYTES),
) {
    /** Bind one snapshot request and its entire response to one login generation. */
    suspend fun read(): AccountUsageDocuments =
        withContext(Dispatchers.IO) {
            val revision = account.revision
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                val snapshot = read(AccountUsageResource.SNAPSHOT, revision)
                AccountUsageDocuments(
                    snapshot.document("luna"),
                    snapshot.document("audio"),
                    snapshot.document("usage"),
                )
            } ?: throw java.net.SocketTimeoutException("Account usage response timeout")
        }

    private suspend fun read(
        resource: AccountUsageResource,
        revision: Long,
    ): JsonObject {
        val endpoint = OfficialServices.ACCOUNT_BASE_URL + resource.path
        val response =
            account.wire(endpoint, transport, revision).open(
                WireRequest("GET", endpoint, mapOf("Accept" to "application/json"), null),
            )
        try {
            if (response.status !in 200..299) throw AccountRequestFailure(readHttpFailure(response))
            val bytes = response.body.bytes()
            if (bytes.size > MAX_BODY_BYTES) throw AccountUsageFormatFailure()
            return parseDocument(bytes)
        } finally {
            response.body.close()
        }
    }

    private fun JsonObject.document(name: String): JsonObject =
        this[name] as? JsonObject ?: throw AccountUsageFormatFailure()

    private fun parseDocument(bytes: ByteArray): JsonObject {
        val document =
            try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
        return document ?: throw AccountUsageFormatFailure()
    }

    private companion object {
        const val MAX_BODY_BYTES = 262144L
        const val REQUEST_TIMEOUT_MS = 30000L
    }
}

internal enum class AccountUsageResource(
    val path: String,
) {
    SNAPSHOT("/account/usage-snapshot"),
}

/** Internal wire documents: never expose arbitrary response fields in UI or logs. */
internal class AccountUsageDocuments(
    val luna: JsonObject,
    val audio: JsonObject,
    val history: JsonObject,
)

internal class AccountUsageFormatFailure : IOException("Account usage response has an unsupported format")
