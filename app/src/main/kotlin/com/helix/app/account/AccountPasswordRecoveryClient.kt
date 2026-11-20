package com.helix.app.account

import com.helix.provider.api.wire.HttpFailureDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/** Anonymous account endpoints only; no refresh, login, key creation or request replay. */
internal class AccountPasswordRecoveryClient(
    private val account: AccountSession,
) {
    private val operation = Mutex()

    fun remainingCooldownMillis(): Long =
        account.cooldown.remainingMillis(
            -1L,
            OfficialServices.ACCOUNT_BASE_URL + "/auth/password-reset/code",
        )

    suspend fun sendCode(
        email: String,
        proof: AccountCaptchaProof,
    ): Long {
        val normalized = normalizedEmail(email)
        val token = proof.consume(AccountCaptchaAction.PASSWORD_RESET)
        return withContext(Dispatchers.IO) {
            operation.withLock {
                val response =
                    document(
                        account.publicRequest(
                            "password-reset/code",
                            mapOf(
                                "email" to normalized,
                                "captcha_token" to token,
                            ),
                        ),
                    )
                val seconds = ((response["retry_after"] as? JsonPrimitive)?.longOrNull ?: 60L).coerceIn(60L, 86400L)
                account.cooldown.record(
                    -1,
                    OfficialServices.ACCOUNT_BASE_URL + "/auth/password-reset/code",
                    HttpFailureDetails(
                        429,
                        retryAfterMillis =
                            seconds * 1000,
                    ),
                )
                seconds
            }
        }
    }

    suspend fun reset(
        email: String,
        code: String,
        newPassword: String,
        confirmation: String,
    ) {
        val normalized = normalizedEmail(email)
        require(code.matches(Regex("[0-9]{6}")))
        require(newPassword.length in 6..128 && newPassword == confirmation)
        val fields =
            buildJsonObject {
                put("email", normalized)
                put("code", code)
                put("new_password", newPassword)
                put("revoke_api_keys", false)
            }
        withContext(Dispatchers.IO) {
            operation.withLock {
                account.resetAuthentication(normalized) {
                    val response = document(account.publicJsonRequest("password-reset", fields))
                    if ((response["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw AccountDataFailure()
                }
            }
        }
    }

    private fun document(bytes: ByteArray): JsonObject =
        try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw AccountDataFailure()
        } catch (_: IllegalArgumentException) {
            throw AccountDataFailure()
        }

    private fun normalizedEmail(value: String): String =
        value.trim().lowercase(Locale.ROOT).also {
            require(it.length in 3..320 && it.count { char -> char == '@' } == 1 && it.none(Char::isWhitespace))
        }
}
