package com.helix.app.account

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** The manifest is the commit point, so a failed import never replaces the previous avatar. */
internal class LocalUserProfilePersistence(
    private val directory: File,
) {
    private val manifest get() = File(directory, "profile.properties")

    fun read(): LocalUserProfile {
        if (!manifest.exists()) return LocalUserProfile()
        val properties = Properties()
        val name =
            try {
                manifest.inputStream().use { properties.load(it) }
                properties.getProperty("name")?.let(::normalizedProfileName)
            } catch (error: IllegalArgumentException) {
                throw IOException("Invalid local profile", error)
            }
        val avatar = properties.getProperty("avatar")?.takeIf { AVATAR_NAME.matches(it) }
        return LocalUserProfile(name, avatar?.let { File(directory, it) }?.takeIf { it.isFile })
    }

    fun save(profile: LocalUserProfile) {
        ensureDirectory()
        val properties = Properties()
        profile.displayName?.let { properties.setProperty("name", it) }
        profile.avatarFile?.let {
            require(it.parentFile == directory && AVATAR_NAME.matches(it.name))
            properties.setProperty("avatar", it.name)
        }
        val temporary = File.createTempFile("profile-", ".tmp", directory)
        try {
            temporary.outputStream().use { properties.store(it, null) }
            Files.move(
                temporary.toPath(),
                manifest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    fun writeAvatar(bytes: ByteArray): File {
        require(bytes.isNotEmpty() && bytes.size <= MAX_AVATAR_BYTES)
        ensureDirectory()
        val file = File(directory, "avatar-${UUID.randomUUID()}.png")
        try {
            file.writeBytes(bytes)
            return file
        } catch (error: IOException) {
            file.delete()
            throw error
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot save local profile")
    }

    companion object {
        const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
        private val AVATAR_NAME = Regex("avatar-[a-f0-9-]{36}\\.png")
    }
}
