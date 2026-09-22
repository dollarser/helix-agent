package com.helix.extensions.skills.connector.index

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException

/**
 * Verifier for signed connector indexes according to ADR-CONNECTORS-004.
 *
 * Checks:
 * 1. Payload size within bounds (<= 1 MiB).
 * 2. Strict canonical JSON parsing (depth <= 32, no duplicate keys, known schema version).
 * 3. Package entry bounds and format (fixed versions, content hashes, valid HTTPS URLs).
 * 4. Trusted public key resolution by keyId.
 * 5. Monotonic sequence verification: does not restrict downgrade installation by default
 *    (allowDowngrade = true marks isDowngrade = true); callers can reject with allowDowngrade = false.
 * 6. Expiration and validity window checks when clock is available.
 * 7. ECDSA P-256 / SHA-256 detached signature over the exact raw JSON bytes.
 */
class SignedConnectorIndexVerifier {
    @Suppress("LongMethod", "ReturnCount")
    fun verify(
        rawJsonBytes: ByteArray,
        signatureDer: ByteArray,
        trustedKeys: Map<String, PublicKey>,
        lastKnownSequence: Long? = null,
        currentTimeSeconds: Long? = null,
        allowDowngrade: Boolean = true,
    ): ConnectorIndexVerificationResult {
        if (rawJsonBytes.size > ConnectorIndexConstants.MAX_INDEX_BYTES) {
            return ConnectorIndexVerificationResult.Failure(
                FailureReason.PAYLOAD_TOO_LARGE,
                "Payload exceeds maximum allowed index size of ${ConnectorIndexConstants.MAX_INDEX_BYTES} bytes",
            )
        }

        val parseResult = SignedConnectorIndexParser.parse(rawJsonBytes)
        val index =
            when (parseResult) {
                is SignedConnectorIndexParser.ParseResult.Failure -> {
                    return ConnectorIndexVerificationResult.Failure(parseResult.reason, parseResult.detail)
                }

                is SignedConnectorIndexParser.ParseResult.Success -> {
                    parseResult.index
                }
            }

        val publicKey =
            trustedKeys[index.keyId]
                ?: return ConnectorIndexVerificationResult.Failure(
                    FailureReason.UNTRUSTED_KEY,
                    "Key ID '${index.keyId}' is not present in trusted keys",
                )

        var isDowngrade = false
        if (lastKnownSequence != null && index.sequence <= lastKnownSequence) {
            if (!allowDowngrade) {
                return ConnectorIndexVerificationResult.Failure(
                    FailureReason.SEQUENCE_REGRESSION,
                    "Sequence ${index.sequence} is not strictly greater than last known sequence $lastKnownSequence",
                )
            }
            isDowngrade = true
        }

        if (currentTimeSeconds != null) {
            if (currentTimeSeconds < index.issuedAt) {
                return ConnectorIndexVerificationResult.Failure(
                    FailureReason.INDEX_NOT_YET_VALID,
                    "Index not valid yet: current time $currentTimeSeconds is before issuedAt ${index.issuedAt}",
                )
            }
            if (currentTimeSeconds > index.expiresAt) {
                return ConnectorIndexVerificationResult.Failure(
                    FailureReason.INDEX_EXPIRED,
                    "Index expired: current time $currentTimeSeconds is after expiresAt ${index.expiresAt}",
                )
            }
        }

        val signatureVerified = verifyCryptoSignature(rawJsonBytes, signatureDer, publicKey)
        return when (signatureVerified) {
            is CryptoResult.Valid -> {
                ConnectorIndexVerificationResult.Success(index, isDowngrade = isDowngrade)
            }

            is CryptoResult.InvalidEncoding -> {
                ConnectorIndexVerificationResult.Failure(
                    FailureReason.INVALID_SIGNATURE_ENCODING,
                    signatureVerified.detail,
                )
            }

            is CryptoResult.Failed -> {
                ConnectorIndexVerificationResult.Failure(
                    FailureReason.SIGNATURE_VERIFICATION_FAILED,
                    signatureVerified.detail,
                )
            }
        }
    }

    private sealed interface CryptoResult {
        data object Valid : CryptoResult

        data class InvalidEncoding(
            val detail: String,
        ) : CryptoResult

        data class Failed(
            val detail: String,
        ) : CryptoResult
    }

    private fun verifyCryptoSignature(
        data: ByteArray,
        signatureDer: ByteArray,
        publicKey: PublicKey,
    ): CryptoResult =
        try {
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
            sig.initVerify(publicKey)
            sig.update(data)
            if (sig.verify(signatureDer)) {
                CryptoResult.Valid
            } else {
                CryptoResult.Failed("ECDSA signature did not match payload")
            }
        } catch (e: SignatureException) {
            CryptoResult.InvalidEncoding("Signature DER decoding failed: ${e.message}")
        } catch (e: java.security.GeneralSecurityException) {
            CryptoResult.InvalidEncoding("Cryptographic verification error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            CryptoResult.InvalidEncoding("Cryptographic argument error: ${e.message}")
        }

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /**
         * Signs raw bytes using an ECDSA P-256 private key and returns the DER-encoded signature.
         */
        fun sign(
            data: ByteArray,
            privateKey: PrivateKey,
        ): ByteArray {
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
            sig.initSign(privateKey)
            sig.update(data)
            return sig.sign()
        }
    }
}
