package com.helix.extensions.skills.connector.index

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Metadata entry for a single connector package declared in a signed index.
 * Only fixed versions and content addresses are accepted (no "latest" or mutable tags).
 */
data class ConnectorIndexEntry(
    val packageId: String,
    val version: String,
    val sourceUrl: String,
    val archiveSha256: String,
    val sizeBytes: Long,
    val license: String,
    val sourceNoticeUrl: String? = null,
    val minAppVersion: String? = null,
)

/**
 * Root structure of a verified Connector index.
 */
data class ConnectorIndex(
    val schemaVersion: String,
    val publisherId: String,
    val keyId: String,
    val sequence: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val packages: List<ConnectorIndexEntry>,
)

/**
 * Outcome of verifying a signed connector index.
 */
sealed interface ConnectorIndexVerificationResult {
    data class Success(
        val index: ConnectorIndex,
    ) : ConnectorIndexVerificationResult

    data class Failure(
        val reason: FailureReason,
        val detail: String,
    ) : ConnectorIndexVerificationResult
}

enum class FailureReason {
    PAYLOAD_TOO_LARGE,
    TOO_MANY_PACKAGES,
    INVALID_JSON,
    DUPLICATE_KEY,
    UNKNOWN_SCHEMA_VERSION,
    UNTRUSTED_KEY,
    INVALID_SIGNATURE_ENCODING,
    SIGNATURE_VERIFICATION_FAILED,
    SEQUENCE_REGRESSION,
    INDEX_EXPIRED,
    INDEX_NOT_YET_VALID,
    INVALID_FIELD_VALUE,
    DUPLICATE_PACKAGE_ENTRY,
    EXCESSIVE_NESTING_DEPTH,
}

object ConnectorIndexConstants {
    const val SCHEMA_VERSION_V1 = "helix.connector.index/v1"
    const val MAX_INDEX_BYTES = 1024 * 1024 // 1 MiB
    const val MAX_PACKAGES = 1000
    const val MAX_PACKAGE_SIZE = 16L * 1024 * 1024 // 16 MiB
    const val MAX_JSON_DEPTH = 32
    const val MAX_URL_LENGTH = 2048
    const val MAX_LICENSE_LENGTH = 64
    const val MAX_ID_LENGTH = 128

    val ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")
    val PACKAGE_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$")
    val VERSION_REGEX = Regex("^[0-9]+\\.[0-9]+(\\.[0-9]+)?(-[a-zA-Z0-9_.-]+)?$")
    val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    val LICENSE_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")

    /**
     * Helper to decode an EC SPKI public key from raw bytes.
     */
    fun decodeEcPublicKey(spkiBytes: ByteArray): PublicKey {
        val keySpec = X509EncodedKeySpec(spkiBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(keySpec)
    }
}
