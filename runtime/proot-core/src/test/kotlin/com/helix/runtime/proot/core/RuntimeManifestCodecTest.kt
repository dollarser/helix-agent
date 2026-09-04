package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestCodecTest {
    private val lock =
        RuntimeLock(
            1,
            RuntimeAbi.ARM64_V8A,
            listOf(
                RuntimeComponent(
                    id = "proot",
                    version = "5.2.0",
                    abi = RuntimeAbi.ARM64_V8A,
                    url = "https://packages.example.org/proot.deb",
                    size = 100L,
                    sha256 = SHA,
                    license = RuntimeLicense("GPL-2.0-only", "GPLv2", "licenses/GPL-2.0-only.txt"),
                    source = RuntimeSource(repository = "https://github.com/termux/proot", ref = "v5.2.0"),
                ),
                RuntimeComponent(
                    id = "alpine-rootfs",
                    version = "3.20.3",
                    abi = RuntimeAbi.ARM64_V8A,
                    url = "https://dl-cdn.example.org/rootfs.tar.gz",
                    size = 1_000L,
                    sha256 = SHA,
                    license = RuntimeLicense("Mixed", "Alpine", "licenses/ALPINE-README.txt"),
                    source = RuntimeSource(repository = "https://alpinelinux.org", ref = "v3.20.3"),
                    packages =
                        listOf(
                            RuntimePackageInfo("bash", "5.2.26-r0", "GPL-3.0-or-later"),
                            RuntimePackageInfo("git", "2.45.2-r0", "GPL-2.0-only"),
                            RuntimePackageInfo("python3", "3.12.4-r1", "PSF-2.0"),
                            RuntimePackageInfo("nodejs", "20.15.1-r0", "MIT"),
                            RuntimePackageInfo("ripgrep", "14.1.0-r0", "Unlicense"),
                        ),
                ),
            ),
        )

    private fun manifest(smoke: RuntimeSmokeResult? = null) =
        RuntimeInstallManifest(
            schemaVersion = 1,
            installId = "inst_a1b2c3d4e5f6",
            abi = lock.abi,
            installedAtEpochMs = 1_750_000_000_000L,
            lockSha256 = RuntimeLockCodec.sha256Hex(lock),
            lock = lock,
            smoke = smoke,
        )

    @Test
    fun manifestRoundTripPreservesEverything() {
        val withSmoke = manifest(RuntimeSmokeResult(SmokeStatus.PASSED, 1_750_000_000_100L, emptyList()))
        val parsed = RuntimeManifestCodec.parseManifest(RuntimeManifestCodec.encodeManifest(withSmoke))
        assertEquals(withSmoke, parsed)
        assertEquals(RuntimeSmokeResult(SmokeStatus.PASSED, 1_750_000_000_100L, emptyList()), parsed.smoke)

        val withoutSmoke = manifest(null)
        val parsedPlain = RuntimeManifestCodec.parseManifest(RuntimeManifestCodec.encodeManifest(withoutSmoke))
        assertEquals(withoutSmoke, parsedPlain)
        assertNull(parsedPlain.smoke)
    }

    @Test
    fun aTamperedEmbeddedLockIsRejected() {
        val encoded = RuntimeManifestCodec.encodeManifest(manifest())
        val tampered = encoded.replaceFirst("\"version\":\"5.2.0\"", "\"version\":\"9.9.9\"")
        val e = assertThrows(RuntimeLockSchemaException::class.java) { RuntimeManifestCodec.parseManifest(tampered) }
        assertTrue(e.message!!.contains("lockSha256"))
    }

    @Test
    fun aTamperedLockSha256FieldIsRejected() {
        val encoded = RuntimeManifestCodec.encodeManifest(manifest())
        // Zero out the REAL lockSha256 value (a different, still well-formed hash than the
        // embedded lock produces) — parse must catch the integrity mismatch.
        val tampered =
            Regex("\"lockSha256\":\"[0-9a-f]{64}\"")
                .replaceFirst(encoded, "\"lockSha256\":\"" + "0".repeat(64) + "\"")
        assertNotEquals(encoded, tampered)
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeManifestCodec.parseManifest(tampered) }
    }

    @Test
    fun malformedInstallIdsAreRejected() {
        listOf("inst_ABCDEF012345", "inst_a1b2c3d4e5f", "js_a1b2c3d4e5f6", "inst_a1b2c3d4e5f6g").forEach {
            val e =
                assertThrows(RuntimeLockSchemaException::class.java) {
                    RuntimeManifestCodec.checkInstallId(it)
                }
            assertTrue(e.message!!.contains("install id"))
        }
        RuntimeManifestCodec.checkInstallId("inst_a1b2c3d4e5f6")
    }

    @Test
    fun anUnsupportedSchemaVersionIsRejected() {
        val encoded = RuntimeManifestCodec.encodeManifest(manifest())
        val tampered = encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
        val e = assertThrows(RuntimeLockSchemaException::class.java) { RuntimeManifestCodec.parseManifest(tampered) }
        assertTrue(e.message!!.contains("schemaVersion"))
    }

    @Test
    fun smokeStatusAndFailuresMustAgree() {
        // PASSED carrying failures is inconsistent.
        val withFailures =
            RuntimeManifestCodec
                .encodeManifest(manifest(RuntimeSmokeResult(SmokeStatus.PASSED, 1L, emptyList())))
                .replaceFirst("\"failures\":[]", "\"failures\":[\"x\"]")
        val e1 =
            assertThrows(RuntimeLockSchemaException::class.java) { RuntimeManifestCodec.parseManifest(withFailures) }
        assertTrue(e1.message!!.contains("smoke"))
        // FAILED without failures is inconsistent.
        val failedClean =
            RuntimeManifestCodec
                .encodeManifest(manifest(RuntimeSmokeResult(SmokeStatus.FAILED, 1L, listOf("git --version timed out"))))
                .replaceFirst("\"failures\":[\"git --version timed out\"]", "\"failures\":[]")
        assertThrows(RuntimeLockSchemaException::class.java) { RuntimeManifestCodec.parseManifest(failedClean) }
        // A FAILED smoke with its failure recorded parses fine.
        val failed =
            RuntimeManifestCodec.parseManifest(
                RuntimeManifestCodec.encodeManifest(manifest(RuntimeSmokeResult(SmokeStatus.FAILED, 1L, listOf("x")))),
            )
        assertEquals(SmokeStatus.FAILED, failed.smoke?.status)
    }

    @Test
    fun activationRoundTripAndRejections() {
        val activation = RuntimeActivation(1, "inst_a1b2c3d4e5f6", 1_750_000_000_000L)
        assertEquals(
            activation,
            RuntimeManifestCodec.parseActivation(RuntimeManifestCodec.encodeActivation(activation)),
        )

        val encoded = RuntimeManifestCodec.encodeActivation(activation)
        assertThrows(RuntimeLockSchemaException::class.java) {
            RuntimeManifestCodec.parseActivation(
                encoded.replaceFirst("\"installId\":\"inst_a1b2c3d4e5f6\"", "\"installId\":\"x\""),
            )
        }
        assertThrows(RuntimeLockSchemaException::class.java) {
            RuntimeManifestCodec.parseActivation(encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":9"))
        }
    }

    @Test
    fun manifestAndEmbeddedLockAbiMustAgree() {
        // copy() with a different ABI trips the data-class invariant (fail-closed either way).
        assertThrows(IllegalArgumentException::class.java) { manifest().copy(abi = RuntimeAbi.X86_64) }
    }

    private companion object {
        const val SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
