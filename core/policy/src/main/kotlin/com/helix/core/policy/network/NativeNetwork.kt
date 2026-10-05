package com.helix.core.policy.network

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One authoritative file, read on every lookup in each process. No cached SharedPreferences. */
object NativeNetwork {
    @Volatile
    var settings: NativeDnsSettings? = null

    @Synchronized
    fun initialize(directory: File): NativeDnsSettings {
        settings?.let { return it }
        val file = File(directory, "native-hosts.txt")
        return NativeDnsSettings(
            read = {
                if (file.exists()) {
                    check(file.length() <= MAX_FILE_BYTES) { "DNS configuration is too large" }
                    file.readText()
                } else {
                    null
                }
            },
            write = { value ->
                check(value.toByteArray().size <= MAX_FILE_BYTES) { "DNS configuration is too large" }
                check(directory.isDirectory || directory.mkdirs()) { "DNS directory unavailable" }
                val temporary = File.createTempFile("native-dns-", ".tmp", directory)
                try {
                    temporary.outputStream().use { stream ->
                        stream.write(value.toByteArray())
                        stream.fd.sync()
                    }
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    temporary.delete()
                }
            },
        ).also { settings = it }
    }

    /** Mapping changes resolution only; callers still authorize and pin the resulting addresses. */
    fun resolve(host: String): List<InetAddress> = resolver.lookup(host)

    private val resolver = NativeDnsResolver()

    private const val MAX_FILE_BYTES = 128 * 1024
}
