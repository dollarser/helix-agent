package com.helix.runtime.cli.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CliSubscriptionProvider(
    val wireId: String,
) {
    CODEX("codex"),
    CLAUDE("claude"),
    ANTIGRAVITY("antigravity"),
    COPILOT("copilot"),
    GROK("grok"),
}

internal data class CliSubscriptionSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val expiresAtEpochMillis: Long,
    val accountId: String? = null,
) {
    init {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank()) { "subscription tokens must not be blank" }
        require(expiresAtEpochMillis > 0) { "subscription expiry must be positive" }
    }
}

internal interface CliSecretStore {
    fun put(
        name: String,
        value: String,
    )

    fun get(name: String): String

    fun delete(name: String)

    fun contains(name: String): Boolean
}

internal data class CliCredentialSnapshot(
    val session: CliSubscriptionSession,
    val revision: String,
)

/** Token vault owned by the subscription module; same-UID developer code is trusted (ADR-0049). */
internal class CliSubscriptionCredentialVault(
    private val store: CliSecretStore,
) {
    constructor(context: Context) : this(AndroidCliSecretStore(context))

    fun save(
        provider: CliSubscriptionProvider,
        session: CliSubscriptionSession,
    ) = synchronized(LOCK) { write(provider, session, UUID.randomUUID().toString()) }

    private fun write(
        provider: CliSubscriptionProvider,
        session: CliSubscriptionSession,
        revision: String,
    ) {
        val encoded = CliCredentialCodec.encode(session, revision)
        require(encoded.encodeToByteArray().size <= CliCredentialCodec.MAX_CREDENTIAL_BYTES) {
            "credential blob exceeds limit"
        }
        store.put(alias(provider), encoded)
    }

    fun snapshot(provider: CliSubscriptionProvider): CliCredentialSnapshot =
        synchronized(LOCK) {
            val encoded = store.get(alias(provider))
            val session = CliCredentialCodec.decode(encoded)
            val revision =
                Json
                    .parseToJsonElement(encoded)
                    .jsonObject["revision"]
                    ?.jsonPrimitive
                    ?.content
                    ?: UUID.randomUUID().toString().also { write(provider, session, it) }
            com.helix.runtime.cli.client
                .CliAccountState("LOGGED_IN", revision)
            CliCredentialSnapshot(session, revision)
        }

    fun load(provider: CliSubscriptionProvider): CliSubscriptionSession = snapshot(provider).session

    /** Token refresh cannot resurrect logout or overwrite a newer login or refresh. */
    fun renew(
        provider: CliSubscriptionProvider,
        expected: CliCredentialSnapshot,
        session: CliSubscriptionSession,
    ) {
        synchronized(LOCK) {
            check(contains(provider)) { "Subscription login changed" }
            val current = snapshot(provider)
            check(current.revision == expected.revision) { "Subscription login changed" }
            if (current == expected) write(provider, session, current.revision)
        }
    }

    fun contains(provider: CliSubscriptionProvider): Boolean = synchronized(LOCK) { store.contains(alias(provider)) }

    fun logout(provider: CliSubscriptionProvider) = synchronized(LOCK) { store.delete(alias(provider)) }

    fun logoutIfCurrent(
        provider: CliSubscriptionProvider,
        expected: CliCredentialSnapshot,
    ) = synchronized(LOCK) {
        if (contains(provider) && snapshot(provider) == expected) store.delete(alias(provider))
    }

    fun publicAccounts(): Map<String, com.helix.runtime.cli.client.CliAccountState> =
        synchronized(LOCK) {
            CliSubscriptionProvider.entries.associate { provider ->
                val account =
                    if (!contains(provider)) {
                        com.helix.runtime.cli.client
                            .CliAccountState("LOGGED_OUT")
                    } else {
                        runCatching {
                            com.helix.runtime.cli.client
                                .CliAccountState("LOGGED_IN", snapshot(provider).revision)
                        }.getOrElse {
                            com.helix.runtime.cli.client
                                .CliAccountState("CREDENTIAL_ERROR")
                        }
                    }
                provider.wireId to account
            }
        }

    fun publicStates(): Map<String, String> = publicAccounts().mapValues { it.value.state }

    private companion object {
        val LOCK = Any()
    }
}

private fun alias(provider: CliSubscriptionProvider) = "subscription-${provider.wireId}"

/** Persisted credential format, separate from login-lifetime synchronization. */
private object CliCredentialCodec {
    const val MAX_CREDENTIAL_BYTES = 16 * 1024

    fun encode(
        session: CliSubscriptionSession,
        revision: String,
    ): String =
        buildJsonObject {
            put("version", 3)
            put("revision", revision)
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            session.idToken?.let { put("idToken", it) }
            put("expiresAtEpochMillis", session.expiresAtEpochMillis)
            session.accountId?.let { put("accountId", it) }
        }.toString()

    fun decode(encoded: String): CliSubscriptionSession {
        require(encoded.encodeToByteArray().size <= MAX_CREDENTIAL_BYTES)
        val value = Json.parseToJsonElement(encoded).jsonObject
        val required = setOf("version", "accessToken", "refreshToken", "expiresAtEpochMillis")
        val optional = setOf("idToken", "accountId", "revision")
        require(
            value.keys.containsAll(required) && value.keys.all { it in required || it in optional },
        ) { "subscription credential schema mismatch" }
        require(value.getValue("version").jsonPrimitive.long in 1L..3L) { "unsupported credential version" }
        if (value.getValue("version").jsonPrimitive.long == 3L) {
            val revision =
                value
                    .getValue("revision")
                    .jsonPrimitive
                    .also { require(it.isString) }
                    .content
            com.helix.runtime.cli.client
                .CliAccountState("LOGGED_IN", revision)
        }
        return CliSubscriptionSession(
            value.getValue("accessToken").jsonPrimitive.content,
            value.getValue("refreshToken").jsonPrimitive.content,
            value["idToken"]?.jsonPrimitive?.content,
            value.getValue("expiresAtEpochMillis").jsonPrimitive.long,
            value["accountId"]?.jsonPrimitive?.content,
        )
    }
}

/** AES-256-GCM files under this Runtime's private directory; the key never leaves AndroidKeyStore. */
private class AndroidCliSecretStore(
    context: Context,
) : CliSecretStore {
    private val directory = File(context.filesDir, "subscription-secrets")
    private val keyLock = Any()

    override fun put(
        name: String,
        value: String,
    ) {
        val plain = value.encodeToByteArray()
        require(plain.isNotEmpty() && plain.size <= MAX_SECRET_BYTES) { "credential blob size is invalid" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        val ciphertext = cipher.doFinal(plain)
        directory.mkdirs()
        val target = file(name)
        val temporary = File(directory, "${target.name}-${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use {
            it.write(cipher.iv)
            it.write(ciphertext)
        }
        Os.chmod(temporary.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        try {
            Os.rename(temporary.path, target.path)
        } finally {
            temporary.delete()
        }
    }

    override fun get(name: String): String {
        val source = file(name).also { require(it.isFile) { "credential is not stored" } }
        require(source.length() in (IV_BYTES + 1)..MAX_ENCRYPTED_BYTES) { "credential blob size is invalid" }
        val bytes = source.readBytes()
        require(bytes.size > IV_BYTES) { "credential blob is corrupt" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        val plain =
            try {
                cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
            } catch (error: GeneralSecurityException) {
                throw IllegalArgumentException("credential blob authentication failed", error)
            }
        return plain.toString(Charsets.UTF_8)
    }

    override fun delete(name: String) {
        val target = file(name)
        check(!target.exists() || target.delete()) { "failed to delete credential" }
    }

    override fun contains(name: String): Boolean = file(name).isFile

    private fun file(name: String): File {
        require(name.length in 1..64 && name.all { it.isLetterOrDigit() || it == '-' })
        return File(directory, "$name.enc")
    }

    private fun masterKey(): SecretKey =
        synchronized(keyLock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: createMasterKey()
        }

    private fun createMasterKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }

    private companion object {
        const val MAX_SECRET_BYTES = 16 * 1024
        const val KEY_ALIAS = "helix.cli.subscription.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_ENCRYPTED_BYTES = MAX_SECRET_BYTES + IV_BYTES + GCM_TAG_BITS / 8
    }
}
