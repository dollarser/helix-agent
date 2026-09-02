package com.helix.runtime.quickjs

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * HXA-050 spike capability 7 (roadmap M5 + doc 03 §2.1/§6.3): 16 KiB page-size
 * support evidence on device.
 *
 * Environment fact recorded by this suite (HXA-050 completion record): the API 29 and
 * API 36 arm64-v8a AVDs run with a 4 KiB page size, so a direct 16 KiB runtime check is
 * NOT possible here. The on-device evidence is instead: the `libquickjs.so` that this
 * process ACTUALLY loads (from inside the APK, `base.apk!/lib/<abi>/libquickjs.so`)
 * has ELF PT_LOAD segments aligned to >= 16 KiB — the property that determines whether
 * `dlopen` succeeds on a 16 KiB page-size device. The host-side JVM test
 * ([QuickJsNativeLibraryElfTest]) covers the same property for all four ABIs in the
 * AAR, including x86_64, which this arm64-only environment cannot run on device.
 */
class DeviceEnvironmentSpikeTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun devicePageSizeIsRecorded() {
        val pageSize =
            Runtime
                .getRuntime()
                .exec(arrayOf("getconf", "PAGESIZE"))
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
                .toInt()
        Log.i("helix.spike", "device page size: $pageSize")
        // Environment sanity: a modern arm64 emulator/device is 4 KiB or 16 KiB.
        assertTrue("unexpected page size: $pageSize", pageSize in setOf(4096, 16384))
    }

    @Test
    fun loadedLibQuickJsLoadSegmentsAreAlignedFor16KiBPages() {
        val abi =
            android.os.Build.SUPPORTED_ABIS
                .first()
        val apk = File(context.applicationInfo.sourceDir)
        val elf =
            ZipFile(apk).use { zip ->
                val entry =
                    zip.getEntry("lib/$abi/libquickjs.so")
                        ?: throw AssertionError("base.apk is missing lib/$abi/libquickjs.so")
                zip.getInputStream(entry).readBytes()
            }
        val alignment = ElfLoadSegments.maxLoadSegmentAlignment(elf)
        Log.i("helix.spike", "on-device libquickjs.so ($abi): ${elf.size} bytes, PT_LOAD alignment $alignment")
        assertTrue(
            "on-device $abi libquickjs.so PT_LOAD alignment $alignment < 16 KiB " +
                "(would fail dlopen on 16 KiB page-size devices)",
            alignment >= 16 * 1024,
        )
    }

    @Test
    fun mainThreadNativeStackRegionIsReadable() {
        // Environment fact for the completion record: the instrumentation process'
        // main-thread native stack size (the execution thread HXA-051 creates is
        // sized explicitly, see QuickJsStackSpikeTest).
        val maps = File("/proc/self/maps").readText().lineSequence()
        val stackLine =
            maps.firstOrNull { it.endsWith("[stack]") }
                ?: throw AssertionError("no [stack] region in /proc/self/maps")
        val range = stackLine.substringBefore(' ')
        val (start, end) = range.split("-")
        val sizeBytes = end.toLong(16) - start.toLong(16)
        Log.i(
            "helix.spike",
            "instrumentation main [stack] region: ${stackLine.substringAfterLast(' ')} size=$sizeBytes",
        )
        assertTrue("main [stack] region must be non-empty", sizeBytes > 0)
    }
}
