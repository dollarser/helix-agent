@file:Suppress("TooManyFunctions")

package com.helix.extensions.skills.connector.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedConnectorIndexVerifierTest {
    private val verifier = SignedConnectorIndexVerifier()

    private val trustedKeys =
        mapOf(
            ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_ID to
                ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.public,
        )

    @Test
    fun validIndexAndSignature_verifiesSuccessfully() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
                lastKnownSequence = 5,
                currentTimeSeconds = 1775000000L,
            )

        assertTrue("Expected success but got $result", result is ConnectorIndexVerificationResult.Success)
        val success = result as ConnectorIndexVerificationResult.Success
        assertEquals(10L, success.index.sequence)
        assertEquals(2, success.index.packages.size)
        assertEquals("github-workspace", success.index.packages[0].packageId)
        assertEquals("Apache-2.0", success.index.packages[0].license)
    }

    @Test
    fun tamperedPayloadByOneByte_failsSignatureVerification() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val tamperedBytes = bytes.copyOf()
        tamperedBytes[tamperedBytes.size - 2] =
            if (tamperedBytes[tamperedBytes.size - 2] == ' '.code.toByte()) {
                '\n'.code.toByte()
            } else {
                ' '.code.toByte()
            }

        val result =
            verifier.verify(
                rawJsonBytes = tamperedBytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
            )

        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        val failure = result as ConnectorIndexVerificationResult.Failure
        assertEquals(FailureReason.SIGNATURE_VERIFICATION_FAILED, failure.reason)
    }

    @Test
    fun signedWithDifferentKey_failsSignatureVerification() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val altSig =
            SignedConnectorIndexVerifier.sign(
                bytes,
                ConnectorIndexTestFixtures.TEST_FIXTURE_ALT_KEY_PAIR.private,
            )

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = altSig,
                trustedKeys = trustedKeys,
            )

        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.SIGNATURE_VERIFICATION_FAILED,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun keyIdNotInTrustedKeys_failsUntrustedKey() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(keyId = "unknown-key-999")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
            )

        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.UNTRUSTED_KEY,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun corruptedSignatureDer_failsInvalidSignatureEncoding() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val corruptSig = byteArrayOf(0x30, 0x05, 0x01, 0x02, 0x03)

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = corruptSig,
                trustedKeys = trustedKeys,
            )

        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.INVALID_SIGNATURE_ENCODING,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun sequenceEqualOrSmallerThanLastKnown_withDowngradeDisallowed_failsSequenceRegression() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(sequence = 10)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val equalResult =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
                lastKnownSequence = 10,
                allowDowngrade = false,
            )
        assertTrue(equalResult is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.SEQUENCE_REGRESSION,
            (equalResult as ConnectorIndexVerificationResult.Failure).reason,
        )

        val smallerResult =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
                lastKnownSequence = 11,
                allowDowngrade = false,
            )
        assertTrue(smallerResult is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.SEQUENCE_REGRESSION,
            (smallerResult as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun downgradeInstallation_isNotRestrictedByDefault() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(sequence = 5)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
                lastKnownSequence = 10,
            )

        assertTrue(
            "Downgrade installation should succeed by default",
            result is ConnectorIndexVerificationResult.Success,
        )
        val success = result as ConnectorIndexVerificationResult.Success
        assertEquals(5L, success.index.sequence)
        assertTrue("isDowngrade flag should be true for downgrade index", success.isDowngrade)
    }

    @Test
    fun expiredIndex_failsIndexExpired() {
        val json =
            ConnectorIndexTestFixtures.sampleValidIndexJson(
                issuedAt = 1770000000L,
                expiresAt = 1775000000L,
            )
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
                currentTimeSeconds = 1776000000L,
            )
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.INDEX_EXPIRED,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun futureIssuedAt_failsIndexNotYetValid() {
        val json =
            ConnectorIndexTestFixtures.sampleValidIndexJson(
                issuedAt = 1780000000L,
                expiresAt = 1790000000L,
            )
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result =
            verifier.verify(
                rawJsonBytes = bytes,
                signatureDer = sig,
                trustedKeys = trustedKeys,
                currentTimeSeconds = 1775000000L,
            )
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.INDEX_NOT_YET_VALID,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun duplicatePackageEntry_failsDuplicatePackageEntry() {
        val dupPackages =
            """
            [
              {
                "packageId": "dup-pkg",
                "version": "1.0.0",
                "sourceUrl": "https://example.com/p1.zip",
                "archiveSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sizeBytes": 1024,
                "license": "MIT"
              },
              {
                "packageId": "dup-pkg",
                "version": "1.0.0",
                "sourceUrl": "https://example.com/p2.zip",
                "archiveSha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "sizeBytes": 2048,
                "license": "Apache-2.0"
              }
            ]
            """.trimIndent()
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(packagesJson = dupPackages)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result = verifier.verify(rawJsonBytes = bytes, signatureDer = sig, trustedKeys = trustedKeys)
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.DUPLICATE_PACKAGE_ENTRY,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun duplicateJsonKey_failsDuplicateKey() {
        val dupKeyJson =
            """
            {
              "schemaVersion": "helix.connector.index/v1",
              "publisherId": "test-pub",
              "keyId": "${ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_ID}",
              "sequence": 1,
              "sequence": 2,
              "issuedAt": 1770000000,
              "expiresAt": 1780000000,
              "packages": []
            }
            """.trimIndent()
        val bytes = dupKeyJson.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result = verifier.verify(rawJsonBytes = bytes, signatureDer = sig, trustedKeys = trustedKeys)
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.DUPLICATE_KEY,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun unknownSchemaVersion_failsUnknownSchemaVersion() {
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(schemaVersion = "helix.connector.index/v2")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result = verifier.verify(rawJsonBytes = bytes, signatureDer = sig, trustedKeys = trustedKeys)
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.UNKNOWN_SCHEMA_VERSION,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun mutableVersionLikeLatest_failsInvalidFieldValue() {
        val badVersionPackages =
            """
            [
              {
                "packageId": "bad-version-pkg",
                "version": "latest",
                "sourceUrl": "https://example.com/pkg.zip",
                "archiveSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sizeBytes": 1024,
                "license": "MIT"
              }
            ]
            """.trimIndent()
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(packagesJson = badVersionPackages)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result = verifier.verify(rawJsonBytes = bytes, signatureDer = sig, trustedKeys = trustedKeys)
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.INVALID_FIELD_VALUE,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun insecureOrInvalidSourceUrl_failsInvalidFieldValue() {
        val httpUrlPackages =
            """
            [
              {
                "packageId": "http-pkg",
                "version": "1.0.0",
                "sourceUrl": "http://insecure.example.com/pkg.zip",
                "archiveSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "sizeBytes": 1024,
                "license": "MIT"
              }
            ]
            """.trimIndent()
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(packagesJson = httpUrlPackages)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result = verifier.verify(rawJsonBytes = bytes, signatureDer = sig, trustedKeys = trustedKeys)
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.INVALID_FIELD_VALUE,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun invalidSha256Hex_failsInvalidFieldValue() {
        val badHashPackages =
            """
            [
              {
                "packageId": "bad-hash-pkg",
                "version": "1.0.0",
                "sourceUrl": "https://example.com/pkg.zip",
                "archiveSha256": "not-a-valid-hex-hash",
                "sizeBytes": 1024,
                "license": "MIT"
              }
            ]
            """.trimIndent()
        val json = ConnectorIndexTestFixtures.sampleValidIndexJson(packagesJson = badHashPackages)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sig = SignedConnectorIndexVerifier.sign(bytes, ConnectorIndexTestFixtures.TEST_FIXTURE_KEY_PAIR.private)

        val result = verifier.verify(rawJsonBytes = bytes, signatureDer = sig, trustedKeys = trustedKeys)
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.INVALID_FIELD_VALUE,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }

    @Test
    fun payloadExceedingLimit_failsPayloadTooLarge() {
        val hugeBytes = ByteArray(ConnectorIndexConstants.MAX_INDEX_BYTES + 1)
        val result =
            verifier.verify(
                rawJsonBytes = hugeBytes,
                signatureDer = byteArrayOf(0),
                trustedKeys = trustedKeys,
            )
        assertTrue(result is ConnectorIndexVerificationResult.Failure)
        assertEquals(
            FailureReason.PAYLOAD_TOO_LARGE,
            (result as ConnectorIndexVerificationResult.Failure).reason,
        )
    }
}
