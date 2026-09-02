package com.helix.runtime.quickjs

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.zip.ZipFile

/**
 * HXA-050 spike (16 KiB page size, doc 03 section 2.1): the Zipline AAR must ship a
 * `libquickjs.so` per ABI whose ELF PT_LOAD segments are aligned to at least 16 KiB,
 * otherwise `dlopen` fails on Android 15+ 16 KiB page-size devices (which run 64-bit
 * ABIs: arm64-v8a / x86_64; the 32-bit ABIs are asserted for the same alignment even
 * though it is not a runtime requirement for them — probe-observed 0x4000 for all four).
 *
 * This JVM test reads the .so bytes from the resolved `app.cash.zipline:zipline-android`
 * AAR (path passed in by the build as the `helix.zipline.aar` system property — same
 * pattern as :core:storage's `helix.schema.dir`). It is the ABORT evidence for the
 * x86_64 ABI, which no on-device test can cover on this arm64-only environment.
 */
class QuickJsNativeLibraryElfTest {
    private val aarFile: String? = System.getProperty("helix.zipline.aar")

    @Test
    fun aarContainsLibQuickJsForAllFourAbis() {
        assumeFalse("helix.zipline.aar system property not set by the build", aarFile.isNullOrEmpty())
        ZipFile(aarFile).use { zip ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                val entry = zip.getEntry("jni/$abi/libquickjs.so")
                assertNotNull("missing jni/$abi/libquickjs.so in zipline-android AAR", entry)
                assertTrue("jni/$abi/libquickjs.so is empty", entry!!.size > 0)
            }
        }
    }

    @Test
    fun arm64LoadSegmentsAreAlignedFor16KiBPages() {
        assertLoadAlignment("jni/arm64-v8a/libquickjs.so", expectedMinAlignment = 16 * 1024)
    }

    @Test
    fun x86_64LoadSegmentsAreAlignedFor16KiBPages() {
        assertLoadAlignment("jni/x86_64/libquickjs.so", expectedMinAlignment = 16 * 1024)
    }

    @Test
    fun armeabiV7AndX86LoadSegmentsAreAlignedFor16KiBPages() {
        assertLoadAlignment("jni/armeabi-v7a/libquickjs.so", expectedMinAlignment = 16 * 1024)
        assertLoadAlignment("jni/x86/libquickjs.so", expectedMinAlignment = 16 * 1024)
    }

    private fun assertLoadAlignment(
        entryName: String,
        expectedMinAlignment: Int,
    ) {
        assumeFalse("helix.zipline.aar system property not set by the build", aarFile.isNullOrEmpty())
        ZipFile(aarFile).use { zip ->
            val entry = zip.getEntry(entryName)
            assertNotNull("missing $entryName in zipline-android AAR", entry)
            val elf = zip.getInputStream(entry!!).readBytes()
            val alignment = ElfLoadSegments.maxLoadSegmentAlignment(elf)
            assertTrue(
                "$entryName PT_LOAD alignment $alignment is below 16 KiB " +
                    "(would fail dlopen on 16 KiB page-size devices)",
                alignment >= expectedMinAlignment,
            )
        }
    }
}
