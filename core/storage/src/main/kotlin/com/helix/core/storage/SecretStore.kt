package com.helix.core.storage

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import com.helix.core.model.SecretAlias
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential storage for provider secrets (security doc section 5.1: Keystore + secret
 * alias; provider doc section 2). The secret value lives only here — never in Room, logs,
 * or UI state (SavedStateHandle); everything else references it through [SecretAlias].
 */
interface SecretStore {
    /** Stores (or overwrites) the secret for [alias]. */
    fun put(
        alias: SecretAlias,
        secret: String,
    )

    /**
     * Returns the secret for [alias]. Throws [IllegalArgumentException] when no secret is
     * stored, the stored file is corrupt, or the master key was reset (fail closed).
     */
    fun get(alias: SecretAlias): String

    /** Deletes the secret for [alias]; idempotent. */
    fun delete(alias: SecretAlias)

    fun contains(alias: SecretAlias): Boolean

    /** All stored aliases. */
    fun aliases(): Set<SecretAlias>
}

/**
 * [SecretStore] backed by a non-exportable Android Keystore AES-256-GCM master key and
 * per-alias encrypted files under the app's private data directory
 * (`filesDir/helix-secrets/<alias>.enc`, mode 0600, layout = IV || ciphertext).
 *
 * The master key is created on first use and never leaves the hardware/TEE-backed
 * AndroidKeyStore; a key reset (factory reset, keystore wipe) makes stored secrets
 * unreadable and every [get] fails closed instead of falling back to plaintext.
 */
class AndroidKeystoreSecretStore internal constructor(
    private val directory: File,
) : SecretStore {
    private val keyLock = Any()

    override fun put(
        alias: SecretAlias,
        secret: String,
    ) {
        requireSecret(secret)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        // GCM generates the IV at init; it must be read back after doFinal and stored with
        // the ciphertext (layout = IV || ciphertext).
        val iv = cipher.iv
        directory.mkdirs()
        val target = File(directory, alias.value + FILE_SUFFIX)
        // A UNIQUE temp name per put (M3 closeout review): a fixed "<name>.tmp" is
        // shared by concurrent puts of the same alias — one put's truncate/rewrite can
        // land in another put's file mid-write, leaving a torn ciphertext that GCM
        // authentication then rejects (the secret is lost, not leaked). The content
        // store already does this (FileContentStore); align.
        val tmp = File(directory, "${target.name}-${java.util.UUID.randomUUID()}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(iv)
            out.write(ciphertext)
        }
        Os.chmod(tmp.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        if (!tmp.renameTo(target)) {
            // Same-directory rename normally succeeds; fall back to atomic-enough copy.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            require(target.isFile) { "failed to persist secret file: ${target.name}" }
        }
    }

    override fun get(alias: SecretAlias): String {
        val file = File(directory, alias.value + FILE_SUFFIX)
        require(file.isFile) { "no secret stored for alias: $alias" }
        val bytes = file.readBytes()
        require(bytes.size > IV_LENGTH) { "secret file is corrupt: ${file.name}" }
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain =
            try {
                cipher.doFinal(ciphertext)
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException(
                    "secret for alias is unreadable (corrupt file or master key reset): $alias",
                    e,
                )
            }
        return String(plain, Charsets.UTF_8)
    }

    override fun delete(alias: SecretAlias) {
        File(directory, alias.value + FILE_SUFFIX).delete()
    }

    override fun contains(alias: SecretAlias): Boolean = File(directory, alias.value + FILE_SUFFIX).isFile

    override fun aliases(): Set<SecretAlias> {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: return emptySet()
        // Foreign files (not written by this store) carry names that are not legal aliases;
        // they are excluded from the listing rather than reported as success.
        val names = files.map { it.name.removeSuffix(FILE_SUFFIX) }.filter { isLegalAliasName(it) }
        return names.map { SecretAlias(it) }.toSet()
    }

    /** [SecretAlias] character-set rules without throwing (pre-filter for on-disk names). */
    private fun isLegalAliasName(name: String): Boolean =
        name.length in 1..SecretAlias.MAX_LENGTH &&
            name.first().isLetterOrDigit() &&
            name.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' }

    /**
     * Returns the non-exportable master key, creating it on first use. Creation and
     * retrieval run on the same lock: a race must never end up with two key attempts or a
     * half-initialized keystore entry.
     */
    private fun masterKey(): SecretKey =
        synchronized(keyLock) {
            (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)
                ?: createMasterKey()
        }

    private fun createMasterKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder =
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setRandomizedEncryptionRequired(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val MAX_SECRET_BYTES = 4096
        private const val KEY_ALIAS = "helix.secrets.master.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val FILE_SUFFIX = ".enc"

        fun create(context: Context): AndroidKeystoreSecretStore =
            AndroidKeystoreSecretStore(File(context.filesDir, "helix-secrets"))

        private fun requireSecret(secret: String) {
            val bytes = secret.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty()) { "secret must not be empty" }
            require(bytes.size <= MAX_SECRET_BYTES) {
                "secret exceeds $MAX_SECRET_BYTES bytes"
            }
        }
    }
}
