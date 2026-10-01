package com.helix.app.mcp.oauth

import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.extensions.mcp.oauth.McpOAuthClientIdentity
import com.helix.extensions.mcp.oauth.McpOAuthClientRegistration
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

private fun hash(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun key(
    issuer: String,
    redirect: String,
) = SecretAlias("oauth.client.registration.${hash("$issuer\n$redirect")}")

private fun selectionKey(serverId: String) = SecretAlias("oauth.client.selection.${hash(serverId)}")

/** Write-ahead setup records, separate from tokens and permission state. Unknown POSTs are not retried. */
class OAuthClientRegistrations(
    private val secrets: SecretStore,
    private val transport: McpOAuthClientRegistration,
) {
    fun remembered(
        metadata: McpOAuthServerMetadata,
        redirect: String,
    ): McpOAuthClientIdentity? =
        synchronized(LOCK) {
            val record = read(key(metadata.issuer, redirect)) ?: return@synchronized null
            require(record.issuer == metadata.issuer && record.redirect == redirect)
            check(record.clientId != null) { "OAUTH_REGISTRATION_OUTCOME_UNKNOWN" }
            record.identity()
        }

    suspend fun resolve(
        serverId: String,
        metadata: McpOAuthServerMetadata,
        redirect: String,
        supplied: String,
    ): McpOAuthClientIdentity {
        val previous = synchronized(LOCK) { read(selectionKey(serverId)) }
        if (previous?.clientId == supplied) {
            require(
                previous.issuer == metadata.issuer && previous.redirect == redirect,
            ) { "OAUTH_CLIENT_BINDING_CHANGED" }
        }
        val stored = synchronized(LOCK) { read(key(metadata.issuer, redirect)) }
        val saved = if (supplied.isBlank() || stored?.clientId == supplied) remembered(metadata, redirect) else null
        val identity =
            saved ?: if (previous?.clientId == supplied) {
                previous.identity()
            } else {
                McpOAuthClientIdentity(metadata.issuer, redirect, supplied, "preregistered")
            }
        val publication = synchronized(LOCK) { begin(serverId, metadata.issuer, redirect, replaceRegistry = false) }
        val verified =
            if (identity.source == "cimd") {
                transport.validateDocument(metadata, identity.clientId, redirect)
            } else {
                identity
            }
        currentCoroutineContext().ensureActive()
        synchronized(LOCK) {
            requireCurrent(publication)
            write(selectionKey(serverId), Record.from(verified))
        }
        return verified
    }

    suspend fun useDocument(
        serverId: String,
        metadata: McpOAuthServerMetadata,
        redirect: String,
        url: String,
    ): McpOAuthClientIdentity {
        val publication = synchronized(LOCK) { begin(serverId, metadata.issuer, redirect, replaceRegistry = true) }
        val identity = transport.validateDocument(metadata, url, redirect)
        currentCoroutineContext().ensureActive()
        synchronized(LOCK) {
            requireCurrent(publication)
            write(key(metadata.issuer, redirect), Record.from(identity))
            write(selectionKey(serverId), Record.from(identity))
        }
        return identity
    }

    suspend fun register(
        serverId: String,
        metadata: McpOAuthServerMetadata,
        redirect: String,
    ): McpOAuthClientIdentity {
        currentCoroutineContext().ensureActive()
        require(
            !metadata.clientIdMetadataDocumentSupported && metadata.registrationEndpoint != null &&
                metadata.supportsS256(),
        )
        val alias = key(metadata.issuer, redirect)
        val pending = Record(metadata.issuer, redirect, null, "dynamic", UUID.randomUUID().toString())
        val publication =
            synchronized(LOCK) {
                check(read(alias) == null) { "OAUTH_REGISTRATION_ALREADY_EXISTS_OR_UNKNOWN" }
                write(alias, pending)
                begin(serverId, metadata.issuer, redirect, replaceRegistry = true)
            }
        val identity = transport.register(metadata, redirect)
        currentCoroutineContext().ensureActive()
        synchronized(LOCK) {
            requireCurrent(publication)
            check(read(alias) == pending) { "OAUTH_REGISTRATION_REPLACED" }
            write(alias, Record.from(identity))
            write(selectionKey(serverId), Record.from(identity))
        }
        return identity
    }

    /** Explicit local forget; it does not claim remote deregistration or revoke an access token. */
    fun forget(
        serverId: String,
        issuer: String,
        redirect: String,
    ) = synchronized(LOCK) {
        begin(serverId, issuer, redirect, replaceRegistry = true)
        secrets.delete(key(issuer, redirect))
        secrets.delete(selectionKey(serverId))
    }

    /** Durable publication epochs prevent a delayed response from undoing forget or a newer selection. */
    private fun begin(
        serverId: String,
        issuer: String,
        redirect: String,
        replaceRegistry: Boolean,
    ): Publication {
        val selection = SecretAlias("${selectionKey(serverId).value}.epoch")
        val registry = SecretAlias("${key(issuer, redirect).value}.epoch")
        val selectionEpoch = UUID.randomUUID().toString()
        secrets.put(selection, selectionEpoch)
        if (replaceRegistry || !secrets.contains(registry)) secrets.put(registry, UUID.randomUUID().toString())
        return Publication(selection, selectionEpoch, registry, secrets.get(registry))
    }

    private fun requireCurrent(publication: Publication) {
        check(
            secrets.get(publication.selection) == publication.selectionEpoch &&
                secrets.get(publication.registry) == publication.registryEpoch,
        ) { "OAUTH_CLIENT_SETUP_REPLACED" }
    }

    private data class Publication(
        val selection: SecretAlias,
        val selectionEpoch: String,
        val registry: SecretAlias,
        val registryEpoch: String,
    )

    private fun read(alias: SecretAlias): Record? =
        if (!secrets.contains(alias)) {
            null
        } else {
            val text = secrets.get(alias)
            require(text.length <= 8192)
            Record.decode(text).also {
                require(it.version == 1)
                if (it.clientId != null) it.identity()
            }
        }

    private fun write(
        alias: SecretAlias,
        record: Record,
    ) {
        val text = record.encode()
        require(text.length <= 8192)
        secrets.put(alias, text)
    }

    private data class Record(
        val issuer: String,
        val redirect: String,
        val clientId: String?,
        val source: String,
        val nonce: String,
        val version: Int = 1,
    ) {
        fun encode(): String =
            buildJsonObject {
                put("version", version)
                put("issuer", issuer)
                put("redirect", redirect)
                put("clientId", clientId)
                put("source", source)
                put("nonce", nonce)
            }.toString()

        fun identity() = McpOAuthClientIdentity(issuer, redirect, requireNotNull(clientId), source)

        companion object {
            fun decode(text: String): Record {
                val root = Json.parseToJsonElement(text).jsonObject
                require(root.keys == setOf("version", "issuer", "redirect", "clientId", "source", "nonce"))
                val version = root.getValue("version").jsonPrimitive
                require(!version.isString && version.int == 1)
                val clientId = root.getValue("clientId").let { if (it == JsonNull) null else root.text("clientId") }
                val nonce = root.text("nonce")
                require(UUID.fromString(nonce).toString() == nonce)
                val record = Record(root.text("issuer"), root.text("redirect"), clientId, root.text("source"), nonce)
                require(clientId != null || record.source == "dynamic")
                return record
            }

            private fun JsonObject.text(key: String): String {
                val value = getValue(key).jsonPrimitive
                require(value.isString && value.content.length <= 2048 && value.content.none { it.isISOControl() })
                return value.content
            }

            fun from(identity: McpOAuthClientIdentity) =
                Record(
                    identity.issuer,
                    identity.redirectUri,
                    identity.clientId,
                    identity.source,
                    UUID.randomUUID().toString(),
                )
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
