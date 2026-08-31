package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizedEndpointTest {
    @Test
    fun parseAcceptsCanonicalForms() {
        assertEquals(
            NormalizedEndpoint("https", "api.openai.com", 443, "/v1"),
            NormalizedEndpoint.parse("https://api.openai.com/v1"),
        )
        assertEquals(
            NormalizedEndpoint("http", "127.0.0.1", 11434, "/v1"),
            NormalizedEndpoint.parse("http://127.0.0.1:11434/v1"),
        )
        // Scheme and host are case-insensitive; stored lowercase.
        assertEquals(
            NormalizedEndpoint("https", "example.com", 443, ""),
            NormalizedEndpoint.parse("HTTPS://EXAMPLE.COM"),
        )
        assertEquals(
            NormalizedEndpoint("http", "::1", 8080, ""),
            NormalizedEndpoint.parse("http://[::1]:8080"),
        )
        // Full-form IPv6 without brackets in the authority (bracketed form is the URL syntax;
        // the normalized host stores the bare literal).
        assertEquals(
            NormalizedEndpoint("https", "1:2:3:4:5:6:7:8", 443, ""),
            NormalizedEndpoint.parse("https://[1:2:3:4:5:6:7:8]"),
        )
        // Embedded-IPv6-with-IPv4 (NAT64) and IPv4-mapped forms.
        assertEquals(
            NormalizedEndpoint("http", "64:ff9b::192.0.2.33", 53, "/v1"),
            NormalizedEndpoint.parse("http://[64:ff9b::192.0.2.33]:53/v1"),
        )
        assertEquals(
            NormalizedEndpoint("https", "1:2:3:4:5:6:1.2.3.4", 443, ""),
            NormalizedEndpoint.parse("https://[1:2:3:4:5:6:1.2.3.4]"),
        )
        // Explicit default ports are kept (deterministic origin).
        assertEquals(
            NormalizedEndpoint("http", "ollama.local", 80, ""),
            NormalizedEndpoint.parse("http://ollama.local:80"),
        )
    }

    @Test
    fun parseRejectsMalformedInput() {
        val bad =
            listOf(
                "",
                "api.openai.com/v1", // missing scheme
                "ftp://x.com", // wrong scheme
                "http://", // empty host
                "http://user:pass@x.com", // userinfo
                "https://x.com/p?q=1", // query
                "https://x.com/p#f", // fragment
                "https://x.com/?q=1", // query with empty path
                "http://x.com ", // trailing space
                "http://x.com/p q", // space in path
                "http://x.com\\p", // backslash
                "http://x.com:0", // port 0
                "http://x.com:0443", // leading zero
                "http://x.com:99999", // out of range
                "http://x.com:65536", // out of range
                "http://x.com:abc", // non-numeric
                "http://x.com:", // empty port
                "http://x_.com", // underscore in hostname
                "http://-x.com", // leading hyphen label
                "http://x..com", // empty label
                "http://x.com.", // trailing dot
                "http://[::1", // unterminated IPv6
                "http://[1:2::3::4]:80", // double compression
                "http://[1:2:3:4:5:6:7:8:9]:80", // too many groups
                "http://[g::1]:80", // non-hex group
                "http://例.com", // non-ASCII host
                "http://x.com/p\u0001", // control char in path
            )
        bad.forEach { raw ->
            assertThrows<IllegalArgumentException>("endpoint parsed but must be rejected: $raw") {
                NormalizedEndpoint.parse(raw)
            }
        }
        // All-digit labels are valid DNS labels (leading zeros included), so quad-like
        // strings that are not legal IPv4 are accepted as hostnames; DNS resolution is a
        // capability-probe concern, not a parse-time concern.
        assertEquals("1.2.3", NormalizedEndpoint.parse("http://1.2.3").host)
        assertEquals("999.1.1.1", NormalizedEndpoint.parse("http://999.1.1.1").host)
        assertEquals("010.0.0.1", NormalizedEndpoint.parse("http://010.0.0.1").host)
    }

    @Test
    fun originAndFullAreCanonical() {
        val endpoint = NormalizedEndpoint.parse("HTTPS://API.OpenAI.com:443/v1/responses")
        assertEquals("https://api.openai.com:443", endpoint.origin)
        assertEquals("https://api.openai.com:443/v1/responses", endpoint.full)
        val bare = NormalizedEndpoint.parse("https://example.org")
        assertEquals("https://example.org:443", bare.origin)
        assertEquals("https://example.org:443", bare.full)
    }

    @Test
    fun loopbackAddressesClassifyOnDevice() {
        listOf("http://127.0.0.1:11434", "http://127.8.9.10", "http://[::1]:8080", "http://localhost:3000").forEach {
            assertEquals(it, ProviderResidence.ON_DEVICE_LOOPBACK, NormalizedEndpoint.parse(it).residence())
        }
    }

    @Test
    fun privateRangesClassifyUserAuthorizedLan() {
        listOf(
            "http://10.0.2.2:8080", // emulator host bridge: a 10/8 address, NOT loopback
            "http://10.255.255.255",
            "http://172.16.0.1",
            "http://172.31.255.254",
            "http://192.168.1.1",
            "http://169.254.1.1",
            "http://[fe80::1]:9000",
            "http://[fe9f::abcd]:9000",
            "http://[fc00::1]:9000",
            "http://[fdff:ffff::1]:9000",
            "http://ollama.local:11434",
            "http://router.lan",
            "http://gateway.intranet:8080",
            "http://printer.home.arpa",
            "http://box.internal:443",
            "http://mDNS.localdomain",
            "http://ollama", // single-label local name
        ).forEach {
            assertEquals(it, ProviderResidence.USER_AUTHORIZED_LAN, NormalizedEndpoint.parse(it).residence())
        }
    }

    @Test
    fun publicHostnamesClassifyPublicCloud() {
        listOf(
            "https://api.openai.com/v1",
            "https://api.anthropic.com",
            "https://api.deepseek.com:443/v1",
            "https://ollama.example.com:11434", // a real domain, even with an Ollama port
        ).forEach {
            assertEquals(it, ProviderResidence.PUBLIC_CLOUD, NormalizedEndpoint.parse(it).residence())
        }
    }

    @Test
    fun barePublicIpLiteralsClassifyCustomRemoteUnknown() {
        listOf(
            "http://8.8.8.8:8080",
            "http://93.184.216.34",
            "http://172.15.0.1", // just outside 172.16/12
            "http://172.32.0.1", // just outside 172.16/12
            "http://128.0.0.1", // just outside 127/8
            "http://[2001:db8::1]:8080",
            "http://[fea0::1]:8080", // beyond fe80::/10
            "http://[feaf::1]:8080",
        ).forEach {
            assertEquals(it, ProviderResidence.CUSTOM_REMOTE_UNKNOWN, NormalizedEndpoint.parse(it).residence())
        }
    }

    @Test
    fun sameTemplateDifferentEndpointsClassifyDifferently() {
        // doc 07 section 7.2: the same Ollama/SGLang template pointed at loopback, LAN and
        // public must classify differently; the template name is never consulted.
        val template = "http://%s:11434/v1"
        assertEquals(
            ProviderResidence.ON_DEVICE_LOOPBACK,
            NormalizedEndpoint.parse(template.format("127.0.0.1")).residence(),
        )
        assertEquals(
            ProviderResidence.USER_AUTHORIZED_LAN,
            NormalizedEndpoint.parse(template.format("192.168.1.50")).residence(),
        )
        assertEquals(
            ProviderResidence.PUBLIC_CLOUD,
            NormalizedEndpoint.parse(template.format("ollama.example.com")).residence(),
        )
    }
}
