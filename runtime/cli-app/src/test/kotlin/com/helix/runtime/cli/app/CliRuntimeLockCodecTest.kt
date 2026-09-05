package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CliRuntimeLockCodecTest {
    private val valid = """{"lockVersion":1,"abi":"arm64-v8a","artifacts":[{"id":"node","version":"1","kind":"runtime","bundled":false,"url":"https://example.com/node","size":1,"sha256":"${"a".repeat(
        64,
    )}","license":"MIT","licenseUrl":"https://example.com/license","termsUrl":"https://example.com/terms"},{"id":"codex-app-server","version":"1","kind":"official-cli","bundled":false,"url":"https://example.com/codex","size":1,"sha256":"${"b".repeat(
        64,
    )}","license":"Apache-2.0","licenseUrl":"https://example.com/license","termsUrl":"https://example.com/terms"},{"id":"claude-code-npm","version":"1","kind":"official-cli","bundled":false,"url":"https://example.com/claude","size":1,"sha256":"${"c".repeat(
        64,
    )}","license":"LicenseRef","licenseUrl":"https://example.com/license","termsUrl":"https://example.com/terms"},{"id":"claude-code-linux-arm64-musl","version":"1","kind":"official-cli","bundled":false,"url":"https://example.com/claude-native","size":1,"sha256":"${"d".repeat(
        64,
    )}","license":"LicenseRef","licenseUrl":"https://example.com/license","termsUrl":"https://example.com/terms"}]}"""

    @Test fun parsesCanonicalFixedMetadata() {
        val lock = CliRuntimeLockCodec.parse(valid)
        assertEquals(valid, CliRuntimeLockCodec.canonical(lock))
        assertEquals(64, CliRuntimeLockCodec.sha256(lock).length)
        assertFalse(lock.artifacts.any { it.bundled })
    }

    @Test fun rejectsUnknownField() {
        assertThrows(CliRuntimeLockException::class.java) {
            CliRuntimeLockCodec.parse(valid.replace("{\"lockVersion\":1", "{\"extra\":1,\"lockVersion\":1"))
        }
    }

    @Test fun rejectsMutableVersion() {
        assertThrows(CliRuntimeLockException::class.java) {
            CliRuntimeLockCodec.parse(valid.replace("\"version\":\"1\"", "\"version\":\"latest\""))
        }
    }

    @Test fun rejectsBundledExecutableWhenNoCandidatePassesAndroidGate() {
        assertThrows(CliRuntimeLockException::class.java) {
            CliRuntimeLockCodec.parse(valid.replaceFirst("\"bundled\":false", "\"bundled\":true"))
        }
    }

    @Test fun rejectsNonHttpsAndBadHash() {
        assertThrows(CliRuntimeLockException::class.java) {
            CliRuntimeLockCodec.parse(valid.replace("https://example.com/node", "http://example.com/node"))
        }
        assertThrows(CliRuntimeLockException::class.java) {
            CliRuntimeLockCodec.parse(valid.replace("${"a".repeat(64)}", "abc"))
        }
    }
}
