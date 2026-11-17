package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.readHttpFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RegistrationPolicy(
    val enabled: Boolean,
    val requiresCode: Boolean,
)

/** No email is sent until the user explicitly invokes sendRegistrationCode/sendBindingCode. */
internal class AccountProfileClient(
    private val account: AccountSession,
    private val transport: WireClient = account.accountTransport,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun register(
        email: String,
        password: String,
        code: String,
    ) = withContext(Dispatchers.IO) {
        require(
            email.isNotBlank() && email.length <= 320 && password.length in 6..128 && code.matches(Regex("[0-9]{6}")),
        )
        account.publicRequest("register", mapOf("email" to email, "password" to password, "code" to code))
        Unit
    }

    suspend fun registrationPolicy(): RegistrationPolicy {
        val document = parseAccountProfile(account.publicRequest("registration-info"))
        return RegistrationPolicy(
            profileFlag(document, "registration_enabled"),
            profileFlag(document, "email_verification"),
        )
    }

    suspend fun sendRegistrationCode(email: String): Long {
        val delay =
            profileRetryDelay(
                parseAccountProfile(
                    account.publicRequest(
                        "registration-code",
                        mapOf("email" to email),
                    ),
                ),
            )
        rememberCodeCooldown("/auth/registration-code", -1L, delay)
        return delay
    }

    suspend fun refreshUser() = userRequest("/account/me")

    suspend fun sendBindingCode(email: String): Long {
        val revision = account.revision
        val delay = profileRetryDelay(request("/account/email/code", mapOf("email" to email), revision))
        rememberCodeCooldown("/account/email/code", revision, delay)
        return delay
    }

    private fun rememberCodeCooldown(
        path: String,
        revision: Long,
        seconds: Long,
    ) {
        account.cooldown.record(
            revision,
            OfficialServices.ACCOUNT_BASE_URL + path,
            com.helix.provider.api.wire
                .HttpFailureDetails(429, retryAfterMillis = seconds.coerceAtMost(86400L) * 1000),
        )
    }

    suspend fun bindEmail(
        email: String,
        code: String,
    ) {
        require(code.matches(Regex("[0-9]{6}")))
        try {
            userRequest("/account/email/bind", mapOf("email" to email, "code" to code))
        } catch (failure: AccountRequestFailure) {
            if (failure.details.errorCode in setOf("email_already_verified", "email_binding_changed", "email_in_use")) {
                try {
                    refreshUser()
                } catch (_: java.io.IOException) {
                    // Keep the actionable original failure.
                }
            }
            throw failure
        }
    }

    suspend fun changePassword(
        current: String,
        next: String,
    ) {
        require(next.length in 6..128)
        val revision = account.revision
        val username = requireNotNull(account.user.value).username
        request("/account/password", mapOf("current_password" to current, "new_password" to next), revision)
        if (account.revision != revision) throw AccountDataFailure()
        // Password change invalidates the session. Re-authenticate with the original login name.
        account.login(username, next, expectedRevision = revision)
    }

    private suspend fun userRequest(
        path: String,
        fields: Map<String, String>? = null,
    ) {
        val revision = account.revision
        // Serialize profile reads/mutations across clients so an older read cannot undo a completed binding.
        account.profileRequests.withLock {
            val next = AccountUser.parse(request(path, fields, revision)["user"]) ?: throw AccountDataFailure()
            account.updateUser(next, revision)
        }
    }

    private suspend fun request(
        path: String,
        fields: Map<String, String>? = null,
        expectedRevision: Long,
    ): JsonObject =
        withContext(dispatcher) {
            withTimeout(30000) {
                val endpoint = OfficialServices.ACCOUNT_BASE_URL + path
                val bytes =
                    fields?.let {
                        buildJsonObject {
                            it.forEach { (key, value) ->
                                put(key, value)
                            }
                        }.toString().toByteArray()
                    }
                val response =
                    account.wire(endpoint, transport, expectedRevision).open(
                        WireRequest(
                            if (fields ==
                                null
                            ) {
                                "GET"
                            } else {
                                "POST"
                            },
                            endpoint,
                            mapOf("Content-Type" to "application/json"),
                            bytes,
                        ),
                    )
                try {
                    if (response.status !in 200..299) throw AccountRequestFailure(readHttpFailure(response))
                    parseAccountProfile(response.body.bytes())
                } finally {
                    response.body.close()
                }
            }
        }
}

private fun parseAccountProfile(bytes: ByteArray): JsonObject {
    val parsed =
        if (bytes.size <= 65536) {
            try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }
    return parsed ?: throw AccountDataFailure()
}

private fun profileFlag(
    value: JsonObject,
    key: String,
) = (value[key] as? JsonPrimitive)?.content in setOf("true", "1")

private fun profileRetryDelay(value: JsonObject) =
    ((value["retry_after"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 60L).coerceAtLeast(60L)
