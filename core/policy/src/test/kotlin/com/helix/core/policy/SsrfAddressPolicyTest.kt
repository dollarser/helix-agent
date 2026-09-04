package com.helix.core.policy

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-066 (security doc 7.9): the connection-time SSRF / URL-Policy address classifier. Covers the
 * classify() ranges (IPv4/IPv6/IPv4-mapped), and the whole-set decision — a public host that
 * rebinds to loopback/LAN/metadata, a scoped LAN host that rebinds to public, STANDARD vs ADVANCED,
 * and the connect-time peer revalidation. Pure JVM, no Android.
 */
class SsrfAddressPolicyTest {
    // --- classify() ---

    @Test
    fun `classify ipv4 public`() {
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(v4(8, 8, 8, 8)))
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(v4(1, 0, 0, 1)))
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(v4(223, 255, 255, 255)))
    }

    @Test
    fun `classify ipv4 loopback link-local and metadata`() {
        assertEquals(IpAddressClass.LOOPBACK, SsrfAddressPolicy.classify(v4(127, 0, 0, 1)))
        assertEquals(IpAddressClass.LOOPBACK, SsrfAddressPolicy.classify(v4(127, 255, 255, 255)))
        assertEquals(IpAddressClass.LINK_LOCAL, SsrfAddressPolicy.classify(v4(169, 254, 1, 2)))
        assertEquals(IpAddressClass.CLOUD_METADATA, SsrfAddressPolicy.classify(v4(169, 254, 169, 254)))
        assertEquals(IpAddressClass.CLOUD_METADATA, SsrfAddressPolicy.classify(v4(100, 100, 100, 200)))
    }

    @Test
    fun `classify ipv4 private lan ranges`() {
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(v4(10, 0, 0, 5)))
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(v4(172, 16, 0, 1)))
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(v4(172, 31, 255, 255)))
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(v4(192, 168, 1, 1)))
        // just outside the private ranges -> public (172.32 is not in 172.16/12)
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(v4(172, 32, 0, 1)))
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(v4(172, 15, 0, 1)))
    }

    @Test
    fun `classify ipv4 non-routable is other-reserved`() {
        assertEquals(IpAddressClass.OTHER_RESERVED, SsrfAddressPolicy.classify(v4(0, 0, 0, 0)))
        assertEquals(IpAddressClass.OTHER_RESERVED, SsrfAddressPolicy.classify(v4(224, 0, 0, 1)))
        assertEquals(IpAddressClass.OTHER_RESERVED, SsrfAddressPolicy.classify(v4(255, 255, 255, 255)))
        assertEquals(IpAddressClass.OTHER_RESERVED, SsrfAddressPolicy.classify(v4(240, 0, 0, 1)))
    }

    @Test
    fun `classify ipv6 public loopback link-local ula`() {
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(googleDnsV6()))
        assertEquals(IpAddressClass.LOOPBACK, SsrfAddressPolicy.classify(loopbackV6()))
        assertEquals(IpAddressClass.LINK_LOCAL, SsrfAddressPolicy.classify(linkLocalV6()))
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(ulaV6()))
    }

    @Test
    fun `classify ipv6 metadata and unspecified`() {
        assertEquals(IpAddressClass.CLOUD_METADATA, SsrfAddressPolicy.classify(azureMetadataV6()))
        // ::1 is a ULA-range-adjacent but explicitly loopback; unspecified :: is reserved
        assertEquals(IpAddressClass.OTHER_RESERVED, SsrfAddressPolicy.classify(zeroV6()))
    }

    @Test
    fun `classify ipv4-mapped and compatible unwrap to the embedded v4`() {
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(mapped(10, 0, 0, 1)))
        assertEquals(IpAddressClass.LOOPBACK, SsrfAddressPolicy.classify(mapped(127, 0, 0, 1)))
        assertEquals(IpAddressClass.CLOUD_METADATA, SsrfAddressPolicy.classify(mapped(169, 254, 169, 254)))
        assertEquals(IpAddressClass.PUBLIC, SsrfAddressPolicy.classify(mapped(8, 8, 8, 8)))
        // deprecated IPv4-compatible (::a.b.c.d, no ff:ff marker) also unwraps
        val compatible = bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)
        assertEquals(IpAddressClass.PRIVATE_LAN, SsrfAddressPolicy.classify(compatible))
    }

    @Test
    fun `classify wrong-size is malformed`() {
        assertEquals(IpAddressClass.MALFORMED, SsrfAddressPolicy.classify(byteArrayOf(1, 2, 3)))
        assertEquals(IpAddressClass.MALFORMED, SsrfAddressPolicy.classify(ByteArray(8)))
    }

    // --- check(): public host must resolve only to public addresses ---

    @Test
    fun `public host resolving to public is allowed`() {
        val pub = v4(93, 184, 216, 34)
        val r = SsrfAddressPolicy.check(listOf(pub), SafetyProfile.STANDARD, emptySet(), PUBLIC_HOST)
        assertTrue(r is SsrfCheckResult.Allowed && r.connectable.single().contentEquals(pub))
    }

    @Test
    fun `public host rebinding to loopback is denied under standard and advanced`() {
        val rebind = v4(127, 0, 0, 1)
        val std = SsrfAddressPolicy.check(listOf(rebind), SafetyProfile.STANDARD, emptySet(), PUBLIC_HOST)
        assertTrue(std is SsrfCheckResult.Denied && std.code == SsrfDenialCode.NON_PUBLIC_ADDRESS)
        val adv =
            SsrfAddressPolicy.check(
                listOf(rebind),
                SafetyProfile.ADVANCED,
                setOf(NetworkOriginScope("example.com", 80)),
                PUBLIC_HOST,
            )
        assertTrue(adv is SsrfCheckResult.Denied && adv.code == SsrfDenialCode.NON_PUBLIC_ADDRESS)
    }

    @Test
    fun `public host with one clean and one lan candidate fails closed on the whole set`() {
        val r =
            SsrfAddressPolicy.check(
                listOf(v4(93, 184, 216, 34), v4(192, 168, 1, 1)),
                SafetyProfile.STANDARD,
                emptySet(),
                PUBLIC_HOST,
            )
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.NON_PUBLIC_ADDRESS)
    }

    @Test
    fun `public host resolving to metadata is reserved`() {
        val r = SsrfAddressPolicy.check(listOf(v4(169, 254, 169, 254)), SafetyProfile.STANDARD, emptySet(), PUBLIC_HOST)
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.RESERVED_METADATA)
    }

    @Test
    fun `public host resolving to nothing is denied`() {
        val r = SsrfAddressPolicy.check(emptyList(), SafetyProfile.STANDARD, emptySet(), PUBLIC_HOST)
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.NO_ADDRESSES)
    }

    @Test
    fun `bare public ip literal is allowed`() {
        val r = SsrfAddressPolicy.check(listOf(v4(8, 8, 8, 8)), SafetyProfile.STANDARD, emptySet(), PUBLIC_IP)
        assertTrue(r is SsrfCheckResult.Allowed)
    }

    // --- check(): loopback/LAN host ---

    @Test
    fun `loopback host is denied under standard`() {
        val r = SsrfAddressPolicy.check(listOf(v4(127, 0, 0, 1)), SafetyProfile.STANDARD, emptySet(), LOOP_HOST)
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.LAN_NOT_ALLOWED)
    }

    @Test
    fun `loopback host without an exact scope is denied under advanced`() {
        val r =
            SsrfAddressPolicy.check(
                listOf(v4(127, 0, 0, 1)),
                SafetyProfile.ADVANCED,
                emptySet(),
                LOOP_HOST,
            )
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.SCOPE_VIOLATION)
    }

    @Test
    fun `scoped loopback host resolving to loopback is allowed`() {
        val scope = setOf(NetworkOriginScope("127.0.0.1", 80))
        val r = SsrfAddressPolicy.check(listOf(v4(127, 0, 0, 1)), SafetyProfile.ADVANCED, scope, LOOP_HOST)
        assertTrue(r is SsrfCheckResult.Allowed && r.connectable.single().contentEquals(v4(127, 0, 0, 1)))
    }

    @Test
    fun `scoped lan host rebinding to public is denied`() {
        val scope = setOf(NetworkOriginScope("printer.local", 80))
        val r = SsrfAddressPolicy.check(listOf(v4(93, 184, 216, 34)), SafetyProfile.ADVANCED, scope, LOCAL_NAME)
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.SCOPE_VIOLATION)
    }

    @Test
    fun `scoped lan name resolving to private is allowed`() {
        val scope = setOf(NetworkOriginScope("printer.local", 80))
        val r = SsrfAddressPolicy.check(listOf(v4(192, 168, 1, 50)), SafetyProfile.ADVANCED, scope, LOCAL_NAME)
        assertTrue(r is SsrfCheckResult.Allowed)
    }

    @Test
    fun `scoped lan host reaching metadata is reserved even with a scope`() {
        val scope = setOf(NetworkOriginScope("169.254.169.254", 80))
        val ep = NormalizedEndpoint.parse("http://169.254.169.254:80")
        val r = SsrfAddressPolicy.check(listOf(v4(169, 254, 169, 254)), SafetyProfile.ADVANCED, scope, ep)
        assertTrue(r is SsrfCheckResult.Denied && r.code == SsrfDenialCode.RESERVED_METADATA)
    }

    // --- revalidatePeer(): the connect-time peer must still satisfy the same decision ---

    @Test
    fun `peer revalidation accepts a matching peer and refuses a rebound peer`() {
        val ok =
            SsrfAddressPolicy.revalidatePeer(v4(93, 184, 216, 34), SafetyProfile.STANDARD, emptySet(), PUBLIC_HOST)
        assertTrue(ok is SsrfCheckResult.Allowed)
        val rebound =
            SsrfAddressPolicy.revalidatePeer(v4(127, 0, 0, 1), SafetyProfile.STANDARD, emptySet(), PUBLIC_HOST)
        assertTrue(rebound is SsrfCheckResult.Denied && rebound.code == SsrfDenialCode.NON_PUBLIC_ADDRESS)
    }

    // --- helpers ---

    private fun v4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): ByteArray = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private fun mapped(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): ByteArray = bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, a, b, c, d)

    private fun googleDnsV6() = bytes(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0x88, 0x88, 0, 0)

    private fun loopbackV6() = bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

    private fun linkLocalV6() = bytes(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

    private fun ulaV6() = bytes(0xfd, 0x12, 0x34, 0x56, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

    private fun azureMetadataV6() = bytes(0xfd, 0x00, 0x0e, 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54)

    private fun zeroV6() = ByteArray(16)

    private fun bytes(vararg v: Int): ByteArray = v.map { it.toByte() }.toByteArray()

    companion object {
        private val PUBLIC_HOST = NormalizedEndpoint.parse("http://example.com")
        private val PUBLIC_IP = NormalizedEndpoint.parse("http://8.8.8.8:80")
        private val LOOP_HOST = NormalizedEndpoint.parse("http://127.0.0.1:80")
        private val LOCAL_NAME = NormalizedEndpoint.parse("http://printer.local:80")
    }
}
