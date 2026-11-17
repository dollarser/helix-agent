package com.helix.app.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Explicit projection excludes server-only recipients/audit fields and never logs message bodies. */
internal object AccountNoticeParser {
    fun document(bytes: ByteArray): JsonObject =
        try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw AccountNoticesFormatFailure()
        } catch (_: IllegalArgumentException) {
            throw AccountNoticesFormatFailure()
        }

    fun unread(document: JsonObject): Int = integer(document, "unread")

    fun page(
        document: JsonObject,
        expectedPage: Int,
    ): AccountNoticesPage {
        val page = integer(document, "page")
        val size = integer(document, "page_size")
        val total = integer(document, "total")
        val unread = unread(document)
        val items = notices(document)
        validatePageMetadata(page, expectedPage, size)
        if (unread > total || items.size > size) throw AccountNoticesFormatFailure()
        return AccountNoticesPage(page, size, total, unread, items)
    }

    private fun notices(document: JsonObject): List<AccountNotice> {
        val raw = document["items"] as? JsonArray ?: throw AccountNoticesFormatFailure()
        val items = raw.mapNotNull { it as? JsonObject }.map(::notice)
        if (items.size != raw.size || items.map { it.id }.toSet().size != items.size) {
            throw AccountNoticesFormatFailure()
        }
        return items
    }

    private fun notice(item: JsonObject): AccountNotice {
        val id = string(item, "id")
        val priority = string(item, "priority")
        if (!NOTICE_ID.matches(id) || priority !in setOf("normal", "important")) throw AccountNoticesFormatFailure()
        return AccountNotice(
            id,
            string(item, "title"),
            string(item, "body"),
            priority,
            timestamp(item, "starts_at"),
            optionalTimestamp(item, "expires_at"),
            timestamp(item, "published_at"),
            nonNegativeLong(item, "version"),
            optionalTimestamp(item, "read_at"),
        )
    }

    private fun string(
        item: JsonObject,
        name: String,
    ): String {
        val value = item[name] as? JsonPrimitive ?: throw AccountNoticesFormatFailure()
        if (!value.isString) throw AccountNoticesFormatFailure()
        return value.content
    }

    private fun integer(
        item: JsonObject,
        name: String,
    ): Int {
        val value = item[name] as? JsonPrimitive ?: throw AccountNoticesFormatFailure()
        return value.intOrNull?.takeIf { !value.isString && it >= 0 } ?: throw AccountNoticesFormatFailure()
    }

    private fun nonNegativeLong(
        item: JsonObject,
        name: String,
    ): Long {
        val value = item[name] as? JsonPrimitive ?: throw AccountNoticesFormatFailure()
        return value.longOrNull?.takeIf { !value.isString && it >= 0 } ?: throw AccountNoticesFormatFailure()
    }

    // Bound to the civil dates that can be safely rendered and converted to epoch milliseconds.
    private fun timestamp(
        item: JsonObject,
        name: String,
    ): Long {
        val seconds =
            (item[name] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.content
                ?.toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 && it <= MAX_TIMESTAMP_SECONDS.toBigDecimal() }
                ?: throw AccountNoticesFormatFailure()
        // UI renders whole seconds; retain subsecond acceptance without mistaking seconds for milliseconds.
        return seconds.toLong()
    }

    private const val MAX_TIMESTAMP_SECONDS = 253402300799L

    private fun optionalTimestamp(
        item: JsonObject,
        name: String,
    ): Long? = if (item[name] == JsonNull) null else timestamp(item, name)
}

private fun validatePageMetadata(
    page: Int,
    expectedPage: Int,
    size: Int,
) {
    if (page != expectedPage || size != 30) throw AccountNoticesFormatFailure()
}
