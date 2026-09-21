package com.helix.extensions.skills.connector.index

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Deterministic test fixtures for signed connector index verification.
 * Keys are strictly for testing and must never be used in production.
 */
object ConnectorIndexTestFixtures {
    const val TEST_FIXTURE_KEY_ID = "fixture-key-2026-09"
    const val TEST_PUBLISHER_ID = "helix-test-publisher"

    val TEST_FIXTURE_KEY_PAIR: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    val TEST_FIXTURE_ALT_KEY_PAIR: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    fun sampleValidIndexJson(
        schemaVersion: String = ConnectorIndexConstants.SCHEMA_VERSION_V1,
        publisherId: String = TEST_PUBLISHER_ID,
        keyId: String = TEST_FIXTURE_KEY_ID,
        sequence: Long = 10,
        issuedAt: Long = 1770000000L,
        expiresAt: Long = 1780000000L,
        packagesJson: String = samplePackagesJson(),
    ): String =
        """
        {
          "schemaVersion": "$schemaVersion",
          "publisherId": "$publisherId",
          "keyId": "$keyId",
          "sequence": $sequence,
          "issuedAt": $issuedAt,
          "expiresAt": $expiresAt,
          "packages": $packagesJson
        }
        """.trimIndent()

    fun samplePackagesJson(): String =
        """
        [
          {
            "packageId": "github-workspace",
            "version": "1.0.0",
            "sourceUrl": "https://example.com/packages/github-workspace-1.0.0.zip",
            "archiveSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "sizeBytes": 1048576,
            "license": "Apache-2.0",
            "sourceNoticeUrl": "https://example.com/packages/github-workspace-notice.txt",
            "minAppVersion": "0.1.0"
          },
          {
            "packageId": "gitlab-workspace",
            "version": "1.2.0",
            "sourceUrl": "https://example.com/packages/gitlab-workspace-1.2.0.zip",
            "archiveSha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            "sizeBytes": 524288,
            "license": "MIT"
          }
        ]
        """.trimIndent()
}
