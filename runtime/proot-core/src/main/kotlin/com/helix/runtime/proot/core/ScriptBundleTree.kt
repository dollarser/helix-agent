package com.helix.runtime.proot.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Immutable execution material only; the working directory remains the selected real workspace. */
object ScriptBundleTree {
    fun copyVerified(
        source: File,
        destination: File,
        expectedHash: String,
    ) {
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        require(destination.mkdirs())
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val members =
            Files.walk(source.toPath()).use { paths ->
                paths
                    .iterator()
                    .asSequence()
                    .take(1025)
                    .toList()
            }
        require(members.size <= 1024 && !Files.isSymbolicLink(source.toPath()))
        members.sortedBy { source.toPath().relativize(it).toString() }.forEach { path ->
            require(!Files.isSymbolicLink(path))
            val relative = source.toPath().relativize(path)
            val target = destination.toPath().resolve(relative)
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target)
            } else {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 4L * 1024 * 1024)
                val bytes = readMember(path)
                require(bytes.size <= 4 * 1024 * 1024)
                total += bytes.size
                require(total <= 16L * 1024 * 1024)
                val name = relative.toString().replace(File.separatorChar, '/').toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(name.size).array())
                digest.update(name)
                digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
                Files.write(target, bytes)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "SKILL_SNAPSHOT_CHANGED" }
    }

    private fun readMember(path: java.nio.file.Path): ByteArray =
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count >= 0) {
                require(output.size() + count <= 4 * 1024 * 1024)
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
            output.toByteArray()
        }
}
