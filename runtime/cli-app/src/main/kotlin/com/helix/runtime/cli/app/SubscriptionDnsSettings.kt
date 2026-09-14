package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.InetAddress
import java.util.Locale

internal data class SubscriptionDnsEntry(
    val hostname: String,
    val addresses: List<String>,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    fun active(now: Long): Boolean = now >= createdAtMillis && now < expiresAtMillis
}

/** Runtime-private manual configuration. Reading or saving never performs DNS or HTTP I/O. */
internal class SubscriptionDnsSettings(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun entries(): List<SubscriptionDnsEntry> {
        val raw = read() ?: return emptyList()
        return try {
            Json.parseToJsonElement(raw).jsonArray.map { item ->
                val obj = item.jsonObject
                SubscriptionDnsEntry(
                    domain(obj.getValue("host").jsonPrimitive.content),
                    obj.getValue("addresses").jsonArray.map { literal(it.jsonPrimitive.content).hostAddress!! },
                    obj.getValue("created").jsonPrimitive.long,
                    obj.getValue("expires").jsonPrimitive.long,
                ).also { require(it.addresses.isNotEmpty() && it.expiresAtMillis > it.createdAtMillis) }
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        } catch (_: NoSuchElementException) {
            emptyList()
        }
    }

    @Synchronized
    fun save(
        host: String,
        input: String,
        hours: Int,
    ) {
        require(hours in listOf(1, 24, 168))
        val normalized = domain(host)
        val ips =
            input
                .split(Regex("[\\s,;，；]+"))
                .filter(String::isNotBlank)
                .map { literal(it).hostAddress!! }
                .distinct()
        require(ips.isNotEmpty() && ips.size <= 16)
        val now = clock()
        val entry = SubscriptionDnsEntry(normalized, ips, now, now + hours * 3_600_000L)
        persist(entries().filterNot { it.hostname == normalized } + entry)
    }

    @Synchronized
    fun remove(host: String) = persist(entries().filterNot { it.hostname == domain(host) })

    @Synchronized
    fun clear() = persist(emptyList())

    fun lookup(host: String): List<InetAddress>? =
        entries()
            .firstOrNull { it.hostname == domain(host) && it.active(clock()) }
            ?.addresses
            ?.map { literal(it) }

    private fun persist(entries: List<SubscriptionDnsEntry>) {
        write(
            buildJsonArray {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("host", entry.hostname)
                            put("addresses", buildJsonArray { entry.addresses.forEach { add(it) } })
                            put("created", entry.createdAtMillis)
                            put("expires", entry.expiresAtMillis)
                        },
                    )
                }
            }.toString(),
        )
    }

    companion object {
        fun domain(raw: String): String {
            val value = IDN.toASCII(raw.trim().removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(
                value.isNotEmpty() && value.length <= 253 &&
                    value.split('.').all {
                        it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                    },
            )
            return value
        }

        /** Validate a literal before InetAddress so configuration can never resolve another hostname. */
        fun literal(raw: String): InetAddress {
            val ip = raw.trim().removeSurrounding("[", "]")
            if (':' in ip) {
                require(ip.matches(Regex("[0-9a-fA-F:.]+")))
                require("http://[$ip]/".toHttpUrlOrNull() != null)
            } else {
                val parts = ip.split('.')
                require(
                    parts.size == 4 &&
                        parts.all {
                            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
                        },
                )
            }
            return InetAddress.getByName(ip)
        }
    }
}
