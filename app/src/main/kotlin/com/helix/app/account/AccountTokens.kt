package com.helix.app.account

import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/** Deliberately no generated toString/copy/component methods that could expose tokens. */
class AccountTokens(
    val access: String,
    val refresh: String,
    val expires: Long,
    val refreshExpires: Long,
    val identity: String,
) {
    init {
        require(access.isNotBlank() && refresh.isNotBlank())
        require(access.all { it.code in 33..126 } && refresh.all { it.code in 33..126 })
        require(expires > 0 && refreshExpires > 0 && identity.isNotBlank())
    }

    fun encoded(): String =
        buildJsonObject {
            put("access_token", access)
            put("refresh_token", refresh)
            put("expires_at", expires)
            put("refresh_expires_at", refreshExpires)
            put("identity", identity)
        }.toString()

    companion object {
        fun response(
            bytes: ByteArray,
            now: Long,
            identity: String = UUID.randomUUID().toString(),
        ): AccountTokens {
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

            fun expiry(key: String): Long {
                val seconds = value.getValue(key).jsonPrimitive.long
                require(seconds > 0)
                return Math.addExact(now, Math.multiplyExact(seconds, 1000L))
            }
            return AccountTokens(
                value.getValue("access_token").jsonPrimitive.content,
                value.getValue("refresh_token").jsonPrimitive.content,
                expiry("expires_in"),
                expiry("refresh_expires_in"),
                identity,
            )
        }

        fun stored(raw: String): AccountTokens {
            val value = Json.parseToJsonElement(raw).jsonObject
            return AccountTokens(
                value.getValue("access_token").jsonPrimitive.content,
                value.getValue("refresh_token").jsonPrimitive.content,
                value.getValue("expires_at").jsonPrimitive.long,
                value.getValue("refresh_expires_at").jsonPrimitive.long,
                value.getValue("identity").jsonPrimitive.content,
            )
        }
    }
}

interface AccountTokenStorage {
    fun load(): AccountTokens?

    fun save(tokens: AccountTokens)

    fun clear()
}

class AccountSecretStorage(
    private val secrets: SecretStore,
) : AccountTokenStorage {
    override fun load() = if (secrets.contains(ALIAS)) AccountTokens.stored(secrets.get(ALIAS)) else null

    override fun save(tokens: AccountTokens) = secrets.put(ALIAS, tokens.encoded())

    override fun clear() {
        secrets.delete(ALIAS)
        check(!secrets.contains(ALIAS)) { "Account storage clear failed" }
    }

    private companion object {
        val ALIAS = SecretAlias("helix-account-session")
    }
}
