package com.helix.core.storage.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** JCA PBKDF2-HMAC-SHA256 + AES-256-GCM; passwords and plaintext never form an archive entry. */
object CredentialBackupCipher {
    const val MAX_PLAINTEXT = 1_048_576
    private const val ITERATIONS = 600_000
    private const val ALGORITHM = "AES/GCM/NoPadding"

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        contextSha256: String,
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT)
        require(password.size in 12..1024) { "BACKUP_PASSWORD_LENGTH" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        require(contextSha256.matches(Regex("[a-f0-9]{64}")))
        val header = header(salt, iv, contextSha256)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, plaintext, password, salt, iv, header.toString().toByteArray())
        return JsonObject(header + ("ciphertext" to kotlinx.serialization.json.JsonPrimitive(encode(encrypted))))
            .toString()
            .toByteArray()
    }

    fun decrypt(
        envelope: ByteArray,
        password: CharArray,
        contextSha256: String,
    ): ByteArray {
        require(envelope.size <= 2 * MAX_PLAINTEXT && password.size <= 1024)
        val obj = Json.parseToJsonElement(envelope.decodeToString(throwOnInvalidSequence = true)) as JsonObject
        require(obj.keys == setOf("format", "kdf", "iterations", "salt", "iv", "contextSha256", "ciphertext"))
        require(contextSha256.matches(Regex("[a-f0-9]{64}")))
        require(obj.getValue("contextSha256").jsonPrimitive.content == contextSha256) {
            "BACKUP_CREDENTIAL_CONTEXT_CHANGED"
        }
        require(!obj.getValue("format").jsonPrimitive.isString && obj.getValue("format").jsonPrimitive.int == 1)
        require(obj.getValue("kdf").jsonPrimitive.content == "PBKDF2-HMAC-SHA256")
        require(!obj.getValue("iterations").jsonPrimitive.isString)
        require(obj.getValue("iterations").jsonPrimitive.int == ITERATIONS)
        val salt = decode(obj.getValue("salt").jsonPrimitive.content).also { require(it.size == 16) }
        val iv = decode(obj.getValue("iv").jsonPrimitive.content).also { require(it.size == 12) }
        val ciphertext = decode(obj.getValue("ciphertext").jsonPrimitive.content)
        require(ciphertext.size in 16..MAX_PLAINTEXT + 16)
        val aad = header(salt, iv, contextSha256).toString().toByteArray()
        return crypt(Cipher.DECRYPT_MODE, ciphertext, password, salt, iv, aad)
    }

    private fun header(
        salt: ByteArray,
        iv: ByteArray,
        contextSha256: String,
    ): JsonObject =
        buildJsonObject {
            put("format", 1)
            put("kdf", "PBKDF2-HMAC-SHA256")
            put("iterations", ITERATIONS)
            put("salt", encode(salt))
            put("iv", encode(iv))
            put("contextSha256", contextSha256)
        }

    private fun crypt(
        mode: Int,
        input: ByteArray,
        password: CharArray,
        salt: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val specification = PBEKeySpec(password, salt, ITERATIONS, 256)
        val derived =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
            } finally {
                specification.clearPassword()
            }
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(mode, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } finally {
            derived.fill(0)
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
