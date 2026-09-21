package com.helix.app.mcp.oauth

import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

data class McpOAuthAttempt(
    val attemptId: String,
    val serverId: String,
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String,
    val codeVerifier: String = "",
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val resource: String = issuer,
    val revocationEndpoint: String? = null,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs || nowMs < createdAtMs

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    }
}

/** One app-process owner; atomic claim also prevents two store instances consuming the same attempt. */
@Suppress("TooManyFunctions") // One owner for atomic claim and temporary-secret cleanup.
class McpOAuthAttemptStore(
    private val directory: File,
    private val secrets: SecretStore,
) {
    private val aliasPrefix =
        "oauth.attempt." +
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) } + "."

    init {
        check(directory.isDirectory || directory.mkdirs())
    }

    @Synchronized
    fun saveAttempt(attempt: McpOAuthAttempt) {
        require(validState(attempt.state)) { "Invalid OAuth state" }
        require(attempt.codeVerifier.isNotBlank())
        val target = file(attempt.state)
        require(!target.exists()) { "OAuth attempt already exists" }
        val temporary = File.createTempFile("attempt-", ".tmp", directory)
        try {
            secrets.put(verifierAlias(attempt.state), attempt.codeVerifier)
            FileOutputStream(temporary).use { stream ->
                stream.write(McpOAuthAttemptCodec.encode(attempt).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
            if (!target.exists()) secrets.delete(verifierAlias(attempt.state))
        }
    }

    @Synchronized
    @Suppress("ReturnCount") // Reject malformed, missing and expired attempts before consumption.
    fun peekAttempt(
        state: String,
        nowMs: Long = System.currentTimeMillis(),
    ): McpOAuthAttempt? {
        if (!validState(state)) return null
        val attempt = read(file(state), state) ?: return null
        if (attempt.isExpired(nowMs)) {
            cancelAttempt(state)
            return null
        }
        return attempt
    }

    @Synchronized
    @Suppress("ReturnCount") // Distinguish failed validation from a lost atomic claim.
    fun consumeAttempt(
        state: String,
        nowMs: Long = System.currentTimeMillis(),
    ): McpOAuthAttempt? {
        if (peekAttempt(state, nowMs) == null) return null
        val claimed = File(directory, "$state.${java.util.UUID.randomUUID()}.claimed")
        return try {
            Files.move(file(state).toPath(), claimed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val attempt = read(claimed, state) ?: return null
            attempt.copy(codeVerifier = secrets.get(verifierAlias(state)))
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        } finally {
            if (claimed.exists()) {
                Files.delete(claimed.toPath())
                secrets.delete(verifierAlias(state))
            }
        }
    }

    fun verifier(state: String): String {
        require(peekAttempt(state) != null)
        return secrets.get(verifierAlias(state))
    }

    @Synchronized
    fun cancelAttempt(state: String) {
        if (!validState(state)) return
        Files.deleteIfExists(file(state).toPath())
        secrets.delete(verifierAlias(state))
    }

    @Synchronized
    fun cancelServer(serverId: String) {
        directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach { candidate ->
            val state = candidate.name.removeSuffix(".json")
            if (validState(state) && read(candidate, state)?.serverId == serverId) cancelAttempt(state)
        }
    }

    @Synchronized
    fun cleanupExpired(nowMs: Long = System.currentTimeMillis()) {
        directory
            .listFiles()
            .orEmpty()
            .filter {
                it.name.endsWith(".json") || it.name.endsWith(".claimed")
            }.forEach { candidate ->
                val state = candidate.name.substringBefore('.')
                if (validState(state) && read(candidate, state)?.isExpired(nowMs) != false) {
                    Files.deleteIfExists(candidate.toPath())
                    secrets.delete(verifierAlias(state))
                }
            }
        val pending =
            directory
                .listFiles()
                .orEmpty()
                .map { it.name.substringBefore('.') }
                .toSet()
        secrets.aliases().filter { it.value.startsWith(aliasPrefix) }.forEach { alias ->
            if (alias.value.removePrefix(aliasPrefix) !in pending) secrets.delete(alias)
        }
    }

    private fun read(
        candidate: File,
        state: String,
    ): McpOAuthAttempt? {
        if (!Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            require(candidate.length() <= MAX_ATTEMPT_BYTES)
            val bytes = candidate.readOAuthAttemptBytes(MAX_ATTEMPT_BYTES)
            require(bytes.size <= MAX_ATTEMPT_BYTES)
            McpOAuthAttemptCodec.decode(bytes.toString(Charsets.UTF_8)).takeIf { it.state == state }
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (_: NoSuchElementException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun file(state: String): File = File(directory, "$state.json")

    private fun verifierAlias(state: String): SecretAlias = SecretAlias("$aliasPrefix$state")

    private fun validState(state: String): Boolean = state.matches(Regex("[A-Za-z0-9_-]{1,96}"))

    companion object {
        private const val MAX_ATTEMPT_BYTES = 16_384
    }
}

private fun File.readOAuthAttemptBytes(limit: Int): ByteArray =
    inputStream().use { input ->
        val buffer = ByteArray(limit + 1)
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) break
            offset += count
        }
        buffer.copyOf(offset)
    }
