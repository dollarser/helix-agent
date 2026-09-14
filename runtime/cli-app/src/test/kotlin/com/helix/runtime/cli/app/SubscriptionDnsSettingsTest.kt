package com.helix.runtime.cli.app

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SubscriptionDnsSettingsTest {
    @Test fun expiryPersistenceAndRemovalKeepOtherDomainsUntouched() {
        var raw: String? = null
        var now = 1_000L
        val settings = SubscriptionDnsSettings({ raw }, { raw = it }, { now })
        settings.save("CHATGPT.com.", "192.0.2.10, 2001:db8::1", 1)
        assertEquals(2, settings.lookup("chatgpt.com")!!.size)
        assertNull(settings.lookup("auth.openai.com"))
        assertEquals(settings.entries(), SubscriptionDnsSettings({ raw }, {}).entries())
        now += 3_600_000
        assertNull(settings.lookup("chatgpt.com"))
        settings.save("chatgpt.com", "192.0.2.11", 24)
        assertEquals(1, settings.entries().size)
        settings.remove("CHATGPT.COM")
        assertNull(settings.lookup("chatgpt.com"))
        assertEquals("[]", raw)
    }

    @Test fun invalidConfigurationDoesNotReplaceSavedMapping() {
        var raw: String? = null
        val settings = SubscriptionDnsSettings({ raw }, { raw = it })
        settings.save("chatgpt.com", "192.0.2.1", 24)
        val saved = raw
        for (ip in listOf("other.example", "999.1.1.1", "1.2.3.4:443", "fe80::1%wlan0", "", "010.1.1.1")) {
            assertThrows(IllegalArgumentException::class.java) { settings.save("chatgpt.com", ip, 24) }
            assertEquals(saved, raw)
        }
        assertThrows(IllegalArgumentException::class.java) { settings.save("https://chatgpt.com/", "192.0.2.1", 24) }
        assertThrows(IllegalArgumentException::class.java) { settings.save("*.chatgpt.com", "192.0.2.1", 24) }
        assertEquals(saved, raw)
    }

    @Test fun liveOverridesBypassSystemCacheWithoutPollutingIt() {
        var raw: String? = null
        var now = 10L
        val settings = SubscriptionDnsSettings({ raw }, { raw = it }, { now })
        val system = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 90))
        val cache = BoundedDnsCache(Dns { listOf(system) }, { now })
        val previous = SubscriptionDnsOverrides.settings
        try {
            SubscriptionDnsOverrides.settings = settings
            assertEquals(listOf(system), cache.lookup("chatgpt.com"))
            settings.save("chatgpt.com", "192.0.2.1", 1)
            assertEquals("192.0.2.1", cache.lookup("chatgpt.com").single().hostAddress)
            settings.save("chatgpt.com", "192.0.2.2", 1)
            assertEquals("192.0.2.2", cache.lookup("chatgpt.com").single().hostAddress)
            now += 3_600_000
            assertEquals(listOf(system), cache.lookup("chatgpt.com"))
            settings.clear()
            assertEquals(listOf(system), cache.lookup("chatgpt.com"))
        } finally {
            SubscriptionDnsOverrides.settings = previous
        }
    }

    @Test fun corruptStoreAndClockRollbackDoNotActivateOverrides() {
        var raw: String? = "{broken"
        var now = 1_000L
        val settings = SubscriptionDnsSettings({ raw }, { raw = it }, { now })
        assertTrue(settings.entries().isEmpty())
        settings.save("chatgpt.com", "192.0.2.1", 1)
        now = 999
        assertNull(settings.lookup("chatgpt.com"))
    }
}
