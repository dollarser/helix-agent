package com.helix.app.proot

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobSpec
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Developer DEBUG only; a one-use app-private permit enables one fixed acceptance command. */
class DetachedOwnerProbeActivity : Activity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permit = File(noBackupFilesDir, "detached-probe-permit")
        if (!permit.isFile || intent.getStringExtra("token") != permit.readText()) {
            finish()
            return
        }
        check(permit.delete())
        started = true
    }

    override fun onResume() {
        super.onResume()
        if (!started) return
        started = false
        Thread { executeProbe() }.start()
    }

    private fun executeProbe() {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val directory = File(cacheDir, "detached-owner-probe").apply { mkdirs() }
        val manifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(manifest.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val input = File(directory, "input.zip")
        JobZipWriter(input.outputStream()).use { it.writeManifest(manifest) }
        val binding =
            DetachedJobBinding(
                "probe-session",
                "probe-turn",
                "probe-call",
                "job_$suffix",
                "exec_$suffix",
                hash,
            )
        val spec =
            ProotJobSpec(
                binding.executionId,
                binding.jobId,
                ProotJobCommand.Script("sleep 8; printf SURVIVED_OWNER_DEATH"),
                "",
                emptyMap(),
                60_000,
                1_048_576,
                hash,
            )
        val output = File(directory, "output.zip")
        val outputMode = ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        val reply =
            DetachedJobClient(this).submit(
                binding,
                spec,
                60_000,
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY),
                ParcelFileDescriptor.open(output, outputMode),
            )
        check(reply.accepted) { "Probe refused: $reply" }
        val killRequest = File(noBackupFilesDir, "detached-probe-kill")
        killRequest.delete()
        File(noBackupFilesDir, "detached-probe-started")
            .writeText("${Process.myPid()}\n${binding.jobId}\n${binding.executionId}")
        // Host records both PIDs before requesting SIGKILL; no package force-stop is involved.
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!killRequest.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        check(killRequest.exists()) { "Host did not request main-only death" }
        check(killRequest.delete())
        Process.killProcess(Process.myPid())
    }
}
