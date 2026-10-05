package com.helix.core.policy.network

import java.net.InetAddress

class NativeDnsResolver(
    private val upstream: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val addresses: List<InetAddress>,
        val expiresAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun lookup(hostname: String): List<InetAddress> {
        // Overrides are checked before the system cache, never stored in it, on every lookup.
        NativeNetwork.settings?.lookup(hostname)?.let { return it }
        return synchronized(entries) {
            entries[hostname]?.takeIf { it.expiresAtMillis > clock() }?.addresses
        } ?: upstream(hostname).also { addresses ->
            synchronized(entries) {
                if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
                entries[hostname] = Entry(addresses.toList(), clock() + MAX_AGE_MILLIS)
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 8
        const val MAX_AGE_MILLIS = 5 * 60 * 1000L
    }
}
