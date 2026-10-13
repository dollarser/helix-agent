package com.helix.app.diagnostics

import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/** Opt-in kernel metadata for owned synthetic diagnostics; never changes resource acceptance. */
internal fun recordMobileDescriptors(stage: String) {
    if (InstrumentationRegistry.getArguments().getString("helix.soak.diagnose") != "true") return
    val listed = requireNotNull(File("/proc/self/fd").list())
    val links = mutableMapOf<String, String>()
    val fences = mutableMapOf<String, String>()
    val fenceStates = mutableMapOf<String, String>()
    listed.forEach { descriptor ->
        try {
            val target = android.system.Os.readlink("/proc/self/fd/$descriptor")
            links[descriptor] = target
            if (target == "anon_inode:sync_file") {
                fences[descriptor] = File("/proc/self/fdinfo/$descriptor").readText()
                fenceStates[descriptor] = pollFence(descriptor.toInt())
            }
        } catch (closed: android.system.ErrnoException) {
            links[descriptor] = "closed:${closed.errno}"
        } catch (closed: java.io.IOException) {
            fences[descriptor] = "metadata unavailable:${closed.javaClass.simpleName}"
        }
    }
    val facts =
        mapOf(
            "stage" to stage,
            "pid" to Process.myPid(),
            "elapsedMs" to SystemClock.elapsedRealtime(),
            "physicalCount" to listed.size,
            "links" to links,
            "fences" to fences,
            "fenceStates" to fenceStates,
        )
    ApplicationProvider
        .getApplicationContext<android.content.Context>()
        .filesDir
        .resolve("hxa282-fd-phases.jsonl")
        .appendText(JSONObject(facts).toString() + "\n")
    println("MOBILE_FD_PHASE stage=$stage physical=${listed.size} syncFiles=${fences.size}")
}

private fun pollFence(descriptor: Int): String =
    android.os.ParcelFileDescriptor.fromFd(descriptor).use { duplicate ->
        val poll = android.system.StructPollfd()
        poll.fd = duplicate.fileDescriptor
        poll.events =
            android.system.OsConstants.POLLIN
                .toShort()
        android.system.Os.poll(arrayOf(poll), 0)
        "poll=${poll.revents.toInt()}"
    }
