package com.helix.app.account

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/** Only opaque account keys, attempt times and badges survive logout; never notice bodies or credentials. */
internal class AccountNoticeSchedule(
    private val file: File? = null,
) {
    private val values = Properties()

    init {
        try {
            if (file?.isFile == true) file.inputStream().use(values::load)
        } catch (_: IOException) {
            // In-memory throttling still applies if persistence is unavailable.
        } catch (_: IllegalArgumentException) {
            values.clear()
        }
    }

    @Synchronized fun claim(
        account: String,
        now: Long,
    ): Boolean {
        val key = key(account)
        val previous = values.getProperty("$key.attempt")?.toLongOrNull()?.takeIf { it >= 0 }
        if (now < 0 || !isDue(previous, now)) return false
        values.setProperty("$key.attempt", now.toString())
        return persist()
    }

    private fun isDue(
        previous: Long?,
        now: Long,
    ): Boolean = previous == null || (now >= previous && now - previous >= INTERVAL_MILLIS)

    @Synchronized fun unread(account: String): Int? =
        values
            .getProperty(
                "${key(account)}.unread",
            )?.toIntOrNull()
            ?.takeIf { it >= 0 }

    @Synchronized fun record(
        account: String,
        unread: Int,
        checkedAt: Long? = null,
    ) {
        val key = key(account)
        values.setProperty("$key.unread", unread.toString())
        checkedAt?.let { values.setProperty("$key.attempt", it.toString()) }
        persist()
    }

    private fun persist(): Boolean {
        val target = file ?: return true
        return try {
            target.parentFile?.mkdirs()
            val temporary = File(target.path + ".tmp")
            temporary.outputStream().use { values.store(it, null) }
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (_: IOException) {
            // Never make an automatic request without saving its claim across process restarts.
            // The in-memory claim also delays retries; explicit manual operations remain available.
            false
        }
    }

    private fun key(account: String): String =
        MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val INTERVAL_MILLIS = 86_400_000L
    }
}
