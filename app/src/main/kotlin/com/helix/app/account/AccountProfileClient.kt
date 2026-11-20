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
@Suppress("TooManyFunctions") // Cohesive identity endpoints and their shared cooldown boundary.
internal class AccountProfileClient(
    private val account: AccountSession,
    private val transport: WireClient = account.accountTransport,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun remainingCodeCooldownMillis(binding: Boolean): Long =
        account.cooldown.remainingMillis(
            if (binding) account.revision else -1L,
            OfficialServices.ACCOUNT_BASE_URL + if (binding) "/account/email/code" else "/auth/registration-code",
        )

    suspend fun register(
        email: String,
        password: String,
        code: String,
        displayName: String = "",
    ) = withContext(Dispatchers.IO) {
        require(
            email.isNotBlank() && email.length <= 320 && password.length in 6..128 && code.matches(Regex("[0-9]{6}")),
        )
        val fields = mutableMapOf("email" to email, "password" to password, "code" to code)
        if (displayName.isNotBlank()) fields["display_name"] = normalizedDisplayName(displayName)
        account.publicRequest("register", fields)
        Unit
    }

    suspend fun registrationPolicy(): RegistrationPolicy {
        val document = parseAccountProfile(account.publicRequest("registration-info"))
        return RegistrationPolicy(
            profileFlag(document, "registration_enabled"),
            profileFlag(document, "email_verification"),
        )
    }

    suspend fun sendRegistrationCode(
        email: String,
        proof: AccountCaptchaProof,
    ): Long {
        val delay =
            profileRetryDelay(
                parseAccountProfile(
                    account.publicRequest(
                        "registration-code",
                        mapOf("email" to email, "captcha_token" to proof.consume(AccountCaptchaAction.REGISTRATION)),
                    ),
                ),
            )
        rememberCodeCooldown("/auth/registration-code", -1L, delay)
        return delay
    }

    suspend fun refreshUser() = userRequest("/account/me")

    suspend fun sendBindingCode(
        email: String,
        proof: AccountCaptchaProof,
        expectedRevision: Long,
    ): Long {
        val revision = expectedRevision
        if (account.revision != revision) throw AccountDataFailure()
        val fields = mapOf("email" to email, "captcha_token" to proof.consume(AccountCaptchaAction.EMAIL_BINDING))
        val delay = profileRetryDelay(request("/account/email/code", fields, revision))
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
        expectedRevision: Long = account.revision,
    ) {
        require(current.isNotBlank() && current.length <= 4096 && next.isNotBlank() && next.length in 6..128)
        if (account.revision != expectedRevision) throw AccountDataFailure()
        val revision = expectedRevision
        val email =
            requireNotNull(
                account.user.value
                    ?.email
                    ?.takeIf { it.isNotBlank() },
            )
        request("/account/password", mapOf("current_password" to current, "new_password" to next), revision)
        if (account.revision != revision) throw AccountDataFailure()
        // Password change invalidates the session. Re-authenticate with the login email.
        reauthenticateChangedPassword(email, next, revision)
    }

    @Suppress("TooGenericExceptionCaught") // After confirmed mutation, any failed re-login requires manual sign-in.
    private suspend fun reauthenticateChangedPassword(email: String, next: String, revision: Long) {
        try {
            account.login(email, next, expectedRevision = revision)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            account.invalidateChangedPasswordSession(revision)
            throw cancelled
        } catch (_: Exception) {
            account.invalidateChangedPasswordSession(revision)
            throw AccountPasswordChangedSignInRequired()
        }
    }

    suspend fun changeDisplayName(
        name: String,
        expectedRevision: Long = account.revision,
    ) {
        val normalized = normalizedDisplayName(name)
        userRequest(
            "/account/profile",
            mapOf("display_name" to normalized),
            expectedRevision,
            "PATCH",
        )
    }

    private suspend fun userRequest(
        path: String,
        fields: Map<String, String>? = null,
        revision: Long = account.revision,
        method: String = if (fields == null) "GET" else "POST",
    ) {
        // Serialize profile reads/mutations across clients so an older read cannot undo a completed binding.
        account.profileRequests.withLock {
            if (account.revision != revision) throw AccountDataFailure()
            val next = AccountUser.parse(request(path, fields, revision, method)["user"]) ?: throw AccountDataFailure()
            account.updateUser(next, revision)
        }
    }

    private suspend fun request(
        path: String,
        fields: Map<String, String>? = null,
        expectedRevision: Long,
        method: String = if (fields == null) "GET" else "POST",
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
                            method,
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
