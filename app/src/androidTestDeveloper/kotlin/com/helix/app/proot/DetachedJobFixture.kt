package com.helix.app.proot

import android.content.Context
import android.os.ParcelFileDescriptor
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

internal class DetachedJobFixture(
    context: Context,
    script: String,
    durationMs: Long = 60_000,
) {
    private val suffix =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(12)
    val directory = File(context.cacheDir, "detached-$suffix").apply { mkdirs() }
    private val manifest = JobManifestCodec.encode(JobManifest(emptyList()))
    private val hash =
        MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    val binding =
        DetachedJobBinding("session-$suffix", "turn-$suffix", "call-$suffix", "job_$suffix", "exec_$suffix", hash)
    val spec =
        ProotJobSpec(
            binding.executionId,
            binding.jobId,
            ProotJobCommand.Script(script),
            "",
            emptyMap(),
            durationMs,
            1_048_576,
            hash,
        )
    private val input =
        File(directory, "input.zip").also { file ->
            JobZipWriter(file.outputStream()).use { it.writeManifest(manifest) }
        }
    val output = File(directory, "output.zip")

    fun submit(
        client: DetachedJobClient,
        budgetMs: Long = spec.deadlineMs,
    ): DetachedJobClient.Reply =
        client.submit(
            binding,
            spec,
            budgetMs,
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY),
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY),
        )
}
