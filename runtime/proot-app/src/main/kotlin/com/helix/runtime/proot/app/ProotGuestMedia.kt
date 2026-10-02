package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.GuestMedia
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Prepares commands inside the caller's existing Job/PTY; never binds a Service or starts a process. */
internal object ProotGuestMedia {
    data class Prepared(
        val bindings: List<String>,
        val available: Boolean,
    ) {
        fun path(previous: String?): String =
            if (available) {
                GuestMedia.path(previous)
            } else {
                previous
                    ?: GuestMedia.DEFAULT_PATH
            }
    }

    fun prepare(
        context: Context,
        temporary: File,
    ): Prepared {
        val native = File(context.applicationInfo.nativeLibraryDir)
        // The optional bridge must not make unrelated commands fail on unsupported ABIs.
        val system = GuestMedia.systemPaths.filter { File(it).isDirectory }.toSet()
        if (!File(native, "libhelix_ffmpeg.so").isFile || !system.containsAll(setOf("/system", "/apex"))) {
            return Prepared(emptyList(), false)
        }
        verify(context, native)
        val commands = Files.createTempDirectory(temporary.toPath(), "media-cli-").toFile()
        context.assets.open("runtime/media/USAGE.md").use { input ->
            File(commands, "README.md").outputStream().use { input.copyTo(it) }
        }
        GuestMedia.programs.forEach { program ->
            val script = File(commands, program)
            script.writeText(GuestMedia.script(program), Charsets.UTF_8)
            check(
                script.setReadable(true, false) && script.setExecutable(true, false),
            ) { "Cannot prepare media command" }
        }
        return Prepared(GuestMedia.bindings(native.path, commands.path, system), true)
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verify(
        context: Context,
        native: File,
    ) {
        val manifest =
            context.assets.open("runtime/media/candidate.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
        val records = manifest.getValue("files").jsonArray.map { it.jsonObject }
        check(
            records.map { it.getValue("name").jsonPrimitive.content }.toSet() ==
                GuestMedia.libraries + GuestMedia.programs,
        )
        records.forEach { row ->
            val original = row.getValue("name").jsonPrimitive.content
            val name = if (original in GuestMedia.programs) "libhelix_$original.so" else original
            val file = File(native, name)
            check(
                file.length() ==
                    row
                        .getValue("bytes")
                        .jsonPrimitive.content
                        .toLong(),
            ) { "Media payload size mismatch" }
            check(
                hash(file) == row.getValue("sha256").jsonPrimitive.content,
            ) {
                "Media payload does not match the packaged candidate"
            }
        }
    }
}
