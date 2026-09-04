package com.helix.runtime.proot.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.core.RuntimeBaseline
import com.helix.runtime.proot.core.RuntimeInstallManifest
import com.helix.runtime.proot.core.RuntimeLock
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.RuntimeLockSchemaException
import com.helix.runtime.proot.core.RuntimeManifestCodec
import com.helix.runtime.proot.core.requireBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-080 device acceptance: the runtime-lock / manifest schema (唯一版本真相, architecture
 * doc local-code-execution section 6.3) parses and rejects correctly ON the Android runtime
 * (JVM parity is covered by :runtime:proot-core unit tests; this proves the codec, the
 * embedded-asset path and the JSON engine behave identically on API 29/36 arm64).
 *
 * `runtime-lock.sample.json` is a SELF-DESCRIBED TEST FIXTURE (its URLs/hashes are
 * fixture values, not a real build): it exercises the full closed schema, including the
 * product baseline, without claiming any real asset is locked.
 */
@RunWith(AndroidJUnit4::class)
class ProotLockSchemaDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readSample(): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("runtime-lock.sample.json")
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun theSampleLockParsesOnDeviceAndMeetsTheBaseline() {
        val lock: RuntimeLock = RuntimeLockCodec.parse(readSample())
        lock.requireBaseline()
        assertEquals(2, lock.components.size)
        assertEquals("proot", lock.component("proot")?.id)
        assertEquals(
            RuntimeBaseline.REQUIRED_ROOTFS_PACKAGES,
            lock
                .component("alpine-rootfs")
                ?.packages
                ?.map { it.name }
                ?.toSet(),
        )
        // The canonical fingerprint is stable on device (build/installer recompute the same).
        val first = RuntimeLockCodec.sha256Hex(lock)
        assertEquals(first, RuntimeLockCodec.sha256Hex(RuntimeLockCodec.parse(RuntimeLockCodec.encode(lock))))
    }

    @Test
    fun aTamperedSampleLockHashIsRejectedOnDevice() {
        val sample = readSample()
        // The sample asset is pretty-printed (`"sha256": "…"`): tolerate optional whitespace.
        val componentSha =
            Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(sample)!!.groupValues[1]
        // A non-hex 64-char value breaks the canonical SHA-256 form (a zeroed hash would
        // still be well-formed; the installer's integrity check is a separate layer).
        val tampered = sample.replaceFirst(componentSha, "z".repeat(64))
        val e = assertThrows(RuntimeLockSchemaException::class.java) { RuntimeLockCodec.parse(tampered) }
        assertTrue(e.message!!.contains("sha256"))
    }

    @Test
    fun anInstalledManifestEmbedsAndIntegrityChecksTheLockOnDevice() {
        val lock = RuntimeLockCodec.parse(readSample()).requireBaseline()
        val manifest =
            RuntimeInstallManifest(
                schemaVersion = RuntimeManifestCodec.SUPPORTED_SCHEMA_VERSION,
                installId = "inst_deadbeef0001",
                abi = lock.abi,
                installedAtEpochMs = System.currentTimeMillis(),
                lockSha256 = RuntimeLockCodec.sha256Hex(lock),
                lock = lock,
                smoke = null,
            )
        val encoded = RuntimeManifestCodec.encodeManifest(manifest)
        assertEquals(manifest, RuntimeManifestCodec.parseManifest(encoded))
        // Tampering the embedded lock must be caught by the lockSha256 integrity check.
        val tampered = encoded.replaceFirst("\"version\":\"5.2.0\"", "\"version\":\"9.9.9\"")
        val e = assertThrows(RuntimeLockSchemaException::class.java) { RuntimeManifestCodec.parseManifest(tampered) }
        assertTrue(e.message!!.contains("lockSha256"))
    }
}
