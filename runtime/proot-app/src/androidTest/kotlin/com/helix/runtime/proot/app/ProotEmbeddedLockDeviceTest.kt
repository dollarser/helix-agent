package com.helix.runtime.proot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.core.RuntimeAbi
import com.helix.runtime.proot.core.RuntimeBaseline
import com.helix.runtime.proot.core.RuntimeLock
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.requireBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-081 device acceptance: the REAL embedded runtime-lock.json (the 唯一版本真相 produced
 * by scripts/build-proot-assets.sh, not the HXA-080 fixture) parses on the Android runtime,
 * meets the product baseline, and its referenced embedded assets are present in the APK
 * (ELF magic on the PRoot binaries, rootfs archive + license texts readable). No bytes of
 * any asset are executed or extracted here — that is the installer's job (HXA-082).
 *
 * Asset layout embedded under the APK `assets/runtime/` tree:
 *   runtime/runtime-lock.json        the committed source of truth
 *   runtime/proot/proot, loader      the PRoot executable + its statically-linked loader
 *   runtime/proot/lib/libtalloc.so.2, libandroid-shmem.so   the Termux shared libraries
 *   runtime/rootfs/<archive>         the deterministic RootFS archive
 *   runtime/licenses/<file>          the license texts referenced by each component
 *
 * NOTE on the archive name: the build places `...alpine-minirootfs-3.22.5-aarch64.tar.gz`
 * into the assets source, but the Android Gradle asset pipeline auto-expands `.gz` assets
 * and stores them under the `.gz`-stripped name (verified in the built APK). The embedded
 * name is therefore `basename(url) minus ".gz"` — the same rule the HXA-082 installer uses.
 */
@RunWith(AndroidJUnit4::class)
class ProotEmbeddedLockDeviceTest {
    // The APP's assets (the main APK), not the instrumentation APK's assets:
    // the runtime/ tree ships in the app, the test APK only carries its own fixtures.
    private val assets
        get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun readText(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    /** Embedded rootfs archive name: lock URL basename, with the AGP .gz-expansion suffix rule. */
    private fun embeddedArchiveName(lock: RuntimeLock): String {
        val rootfs = lock.component("alpine-rootfs")!!
        val name = rootfs.url.substringAfterLast('/').substringBefore('?')
        return if (name.endsWith(".gz")) name.removeSuffix(".gz") else name
    }

    @Test
    fun theEmbeddedLockParsesAndMeetsTheBaseline() {
        val lock: RuntimeLock = RuntimeLockCodec.parse(readText("runtime/runtime-lock.json"))
        lock.requireBaseline()
        assertEquals(RuntimeAbi.ARM64_V8A, lock.abi)
        // The rootfs packages must include (at least) the required five.
        val names =
            lock
                .component("alpine-rootfs")
                ?.packages
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
        assertTrue(
            "rootfs packages missing required set",
            RuntimeBaseline.REQUIRED_ROOTFS_PACKAGES.all { it in names },
        )
    }

    @Test
    fun theEmbeddedProotBinariesAreElf() {
        for (rel in listOf("runtime/proot/proot", "runtime/proot/loader")) {
            // read(ByteArray) is the only head-read API available on API 29;
            // readBytes(int)/readNBytes are API 33+ and would throw NoSuchMethodError.
            val buf = ByteArray(4)
            val n = assets.open(rel).use { it.read(buf) }
            assertEquals("$rel: could not read the ELF header", 4, n)
            val hex = buf.joinToString(" ") { String.format(java.util.Locale.ROOT, "%02x", it) }
            assertTrue(
                "$rel must start with the ELF magic, got: $hex",
                buf[0].toInt() == 0x7f && buf[1].toInt() == 0x45 &&
                    buf[2].toInt() == 0x4c && buf[3].toInt() == 0x46,
            )
        }
    }

    @Test
    fun theEmbeddedRootfsArchiveAndLicensesArePresent() {
        val lock = RuntimeLockCodec.parse(readText("runtime/runtime-lock.json"))
        val archiveName = embeddedArchiveName(lock)
        assertTrue(
            "rootfs archive missing from APK assets: runtime/rootfs/$archiveName",
            assets.open("runtime/rootfs/$archiveName").use { it.available() > 0 },
        )
        // Every component's license textRef resolves to a non-empty embedded text.
        for (component in lock.components) {
            val textRef = "runtime/" + component.license.textRef
            assertTrue("license text missing or empty for ${component.id}: $textRef", readText(textRef).isNotBlank())
        }
        assertTrue("ALPINE-README missing or empty", readText("runtime/licenses/ALPINE-README.md").isNotBlank())
    }
}
