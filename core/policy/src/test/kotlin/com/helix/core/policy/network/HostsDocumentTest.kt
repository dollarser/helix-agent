package com.helix.core.policy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class HostsDocumentTest {
    @Test fun aliasesCommentsAndMultipleAddressesPersistWithoutExpiry() {
        var raw: String? = null
        val settings = NativeDnsSettings({ raw }, { raw = it })
        val text =
            "# local mappings\r\n192.0.2.1 API.example. mirror.example # alias\r\n" +
                "::1 api.example\n192.0.2.1 api.example"
        settings.save(text)
        assertEquals(text.replace("\r\n", "\n"), raw)
        assertEquals(2, settings.lookup("API.example")!!.size)
        assertEquals("192.0.2.1", settings.lookup("mirror.example")!!.single().hostAddress)
        assertEquals(settings.text(), NativeDnsSettings({ raw }, {}).text())
        settings.save("# cleared\n")
        assertNull(settings.lookup("api.example"))
    }

    @Test fun invalidLinesNeverPartiallyReplaceSavedDocument() {
        var raw: String? = "127.0.0.1 saved.example"
        val settings = NativeDnsSettings({ raw }, { raw = it })
        val saved = raw
        for (line in listOf(
            "999.1.1.1 invalid.example",
            "other.example invalid.example",
            "010.1.1.1 invalid.example",
            "::1%wlan0 host",
            "127.0.0.1",
            "127.0.0.1 https://example.com",
            "127.0.0.1 *.example.com",
            "127.0.0.1 example.com:443",
        )) {
            val document = "# comment\n127.0.0.1 valid.example\n$line"
            assertEquals(
                3,
                HostsDocument
                    .parse(document)
                    .errors
                    .first()
                    .line,
            )
            assertThrows(IllegalArgumentException::class.java) { settings.save(document) }
            assertEquals(saved, raw)
        }
    }

    @Test fun limitsAndCorruptPersistenceAreExplicitFailures() {
        assertEquals(
            HostsError.Reason.TOO_LARGE,
            HostsDocument
                .parse("#".repeat(128 * 1024 + 1))
                .errors
                .single()
                .reason,
        )
        val excessive = (1..17).joinToString("\n") { "192.0.2.$it example.com" }
        assertEquals(
            HostsError.Reason.TOO_MANY_ADDRESSES,
            HostsDocument
                .parse(excessive)
                .errors
                .single()
                .reason,
        )
        assertThrows(IllegalStateException::class.java) { NativeDnsSettings({ "broken" }, {}).lookup("example.com") }
        assertThrows(IOException::class.java) {
            NativeDnsSettings({ "" }, { throw IOException("fixture") }).save("127.0.0.1 localhost")
        }
    }

    @Test fun overridesBypassSystemCacheAndClearRestoresIt() {
        val previous = NativeNetwork.settings
        var raw: String? = null
        var now = 0L
        var calls = 0
        val system = InetAddress.getByName("192.0.2.90")
        val resolver =
            NativeDnsResolver({
                calls++
                listOf(system)
            }, { now })
        val settings = NativeDnsSettings({ raw }, { raw = it })
        try {
            NativeNetwork.settings = settings
            assertEquals(listOf(system), resolver.lookup("example.com"))
            settings.save("192.0.2.1 example.com")
            now = 10_000_000
            assertEquals("192.0.2.1", resolver.lookup("example.com").single().hostAddress)
            settings.save("192.0.2.2 example.com")
            assertEquals("192.0.2.2", resolver.lookup("example.com").single().hostAddress)
            settings.save("")
            assertEquals(listOf(system), resolver.lookup("example.com"))
            assertEquals(2, calls)
        } finally {
            NativeNetwork.settings = previous
        }
    }
}
