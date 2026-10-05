package com.helix.core.policy.network

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

/** Persist hosts text only after validating the entire document. No DNS/network I/O while parsing. */
class NativeDnsSettings(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    fun text(): String = read().orEmpty()

    @Synchronized
    fun save(text: String) {
        val parsed = HostsDocument.parse(text)
        require(parsed.errors.isEmpty()) { "Invalid hosts document" }
        write(text.replace("\r\n", "\n"))
    }

    fun lookup(host: String): List<InetAddress>? {
        if (':' in host) return null
        val parsed = HostsDocument.parse(text())
        check(parsed.errors.isEmpty()) { "Invalid persisted hosts document" }
        return parsed.addresses[domain(host)]?.map(::literal)
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
            } else {
                val parts = ip.split('.')
                require(
                    parts.size == 4 &&
                        parts.all {
                            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
                        },
                )
            }
            return try {
                InetAddress.getByName(ip)
            } catch (failure: java.net.UnknownHostException) {
                throw IllegalArgumentException("Invalid IP literal", failure)
            }
        }
    }
}
