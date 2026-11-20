package com.helix.app.account

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs

internal const val NOTICE_CACHE_MILLIS = 18_000_000L

internal class CachedNoticePage(
    val page: AccountNoticesPage,
    val ageMillis: Long,
)

/** Two bounded first-page projections in app-private no-backup storage; never credentials or raw responses. */
internal class AccountNoticeCache(
    private val directory: File? = null,
) {
    @Suppress("ReturnCount") // Fail-closed validation rejects untrusted or stale cache documents.
    @Synchronized
    fun read(owner: String, filter: AccountNoticeState, wall: Long, monotonic: Long): CachedNoticePage? {
        val file = file(filter) ?: return null
        return try {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > MAX_BYTES) return null
            val value = AccountNoticeParser.document(file.readBytes())
            if (value["owner"]?.jsonPrimitive?.content != ownerHash(owner)) return null
            val age = cacheAge(value, wall, monotonic) ?: return null
            val document = value["page"] as? JsonObject ?: return null
            val page = AccountNoticeParser.page(document, 1)
            if (page.items.any { it.expiresAt != null && it.expiresAt <= wall / 1000 }) return null
            CachedNoticePage(page, age)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Synchronized fun write(
        owner: String,
        filter: AccountNoticeState,
        page: AccountNoticesPage,
        wall: Long,
        monotonic: Long,
    ) {
        val file = file(filter) ?: return
        val bytes =
            buildJsonObject {
                put("owner", ownerHash(owner))
                put("wall", wall)
                put("monotonic", monotonic)
                put("page", page.document())
            }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        try {
            checkNotNull(directory).mkdirs()
            val temporary = File(directory, "${filter.wireValue}.tmp")
            temporary.writeBytes(bytes)
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            // Memory data remains usable. A failed cache write never claims durable success.
        }
    }

    @Synchronized fun invalidate(filter: AccountNoticeState) {
        file(filter)?.delete()
        directory?.let { File(it, "${filter.wireValue}.tmp").delete() }
    }

    @Synchronized fun clear() {
        for (filter in AccountNoticeState.entries) {
            invalidate(filter)
        }
    }

    private fun file(filter: AccountNoticeState): File? = directory?.let { File(it, "${filter.wireValue}.json") }

    private fun ownerHash(owner: String): String =
        MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

private fun AccountNoticesPage.document(): JsonObject =
    buildJsonObject {
        put("page", page)
        put("page_size", pageSize)
        put("total", total)
        put("unread", unread)
        put(
            "items",
            JsonArray(
                items.map { notice ->
                    buildJsonObject {
                        put("id", notice.id)
                        put("title", notice.title)
                        put("body", notice.body)
                        put("priority", notice.priority)
                        put("starts_at", notice.startsAt)
                        put("expires_at", notice.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
                        put("published_at", notice.publishedAt)
                        put("version", notice.version)
                        put("read_at", notice.readAt?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            ),
        )
    }

@Suppress("ReturnCount") // Missing persisted clock fields are cache misses.
private fun cacheAge(value: JsonObject, wall: Long, monotonic: Long): Long? {
    val savedWall = value["wall"]?.jsonPrimitive?.longOrNull ?: return null
    val savedMono = value["monotonic"]?.jsonPrimitive?.longOrNull ?: return null
    val wallAge = wall - savedWall
    val age = monotonic - savedMono
    val validOrigin = savedWall >= 0 && savedMono >= 0
    val validAge = age in 0 until NOTICE_CACHE_MILLIS && wallAge in 0 until NOTICE_CACHE_MILLIS
    return maxOf(age, wallAge).takeIf { validOrigin && validAge && abs(age - wallAge) <= 60_000L }
}
