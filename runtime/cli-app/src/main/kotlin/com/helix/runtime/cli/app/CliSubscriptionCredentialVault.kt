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
}

internal data class CliSubscriptionSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val expiresAtEpochMillis: Long,
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

/** Token vault owned exclusively by the CLI Runtime application UID. */
internal class CliSubscriptionCredentialVault(
    private val store: CliSecretStore,
) {
    constructor(context: Context) : this(AndroidCliSecretStore(context))

    fun save(
        provider: CliSubscriptionProvider,
        session: CliSubscriptionSession,
    ) {
        val encoded = encode(session)
        require(encoded.encodeToByteArray().size <= MAX_CREDENTIAL_BYTES) {
            "credential blob exceeds limit"
        }
        store.put(alias(provider), encoded)
    }

    fun load(provider: CliSubscriptionProvider): CliSubscriptionSession = decode(store.get(alias(provider)))

    fun contains(provider: CliSubscriptionProvider): Boolean = store.contains(alias(provider))

    fun logout(provider: CliSubscriptionProvider) = store.delete(alias(provider))

    fun publicStates(): Map<String, String> =
        CliSubscriptionProvider.entries.associate { provider ->
            provider.wireId to publicState(provider)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun publicState(provider: CliSubscriptionProvider): String {
        if (!contains(provider)) return "LOGGED_OUT"
        return try {
            load(provider)
            "LOGGED_IN"
        } catch (_: Exception) {
            "CREDENTIAL_ERROR"
        }
    }

    private fun alias(provider: CliSubscriptionProvider) = "subscription-${provider.wireId}"

    private fun encode(session: CliSubscriptionSession): String =
        buildJsonObject {
            put("version", 1)
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            session.idToken?.let { put("idToken", it) }
            put("expiresAtEpochMillis", session.expiresAtEpochMillis)
        }.toString()

    private fun decode(encoded: String): CliSubscriptionSession {
        val value = Json.parseToJsonElement(encoded).jsonObject
        val required = setOf("version", "accessToken", "refreshToken", "expiresAtEpochMillis")
        require(
            value.keys == required || value.keys == required + "idToken",
        ) { "subscription credential schema mismatch" }
        require(value.getValue("version").jsonPrimitive.long == 1L) { "unsupported credential version" }
        return CliSubscriptionSession(
            value.getValue("accessToken").jsonPrimitive.content,
            value.getValue("refreshToken").jsonPrimitive.content,
            value["idToken"]?.jsonPrimitive?.content,
            value.getValue("expiresAtEpochMillis").jsonPrimitive.long,
        )
    }

    private companion object {
        const val MAX_CREDENTIAL_BYTES = 16 * 1024
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
