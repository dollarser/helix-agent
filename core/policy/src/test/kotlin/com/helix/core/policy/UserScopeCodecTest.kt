package com.helix.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * HXA-068: [UserScopeCodec] must round-trip EVERY field of EVERY scope subtype (a rehydrated rule
 * scope must equal the live request scope for the Policy Engine's exact match), and must fail
 * closed (decode to null, never a false scope) on malformed storage.
 */
class UserScopeCodecTest {
    // The codec's on-wire separators (U+0001 field / U+0002 list) rebuilt here so the malformed
    // fixtures below have the correct shape and fail for the reason each test names.
    private val fs = 0x0001.toChar().toString()
    private val ls = 0x0002.toChar().toString()

    private val epoch = Instant.parse("2026-01-01T00:00:00Z")

    private fun allScopes(): List<UserScope> =
        listOf(
            WorkspaceScope("ws-1"),
            DocumentTreeScope("content://com.example.documents/tree/doc123", "My Photos"),
            SharedStorageScope(listOf("/sdcard/Movies", "/sdcard/Download")),
            BrowserTabScope("tab-1", 3),
            AutomationSessionScope(
                setOf("com.example.app", "com.example.other"),
                setOf("com.malicious.app"),
                50,
                epoch,
            ),
            RootSessionScope(epoch, epoch.plusSeconds(600), true),
        )

    @Test
    fun everyScopeSubtypeRoundTripsThroughTheCodec() {
        allScopes().forEach { scope ->
            assertEquals("round-trip failed for $scope", scope, UserScopeCodec.decode(UserScopeCodec.encode(scope)))
        }
    }

    @Test
    fun theEncodingIsStableAndPreservesRootOrder() {
        // The Policy Engine matches scopes by data-class `==` (order-sensitive for the All-files
        // roots), so the codec must PRESERVE order, not canonicalize it: same order -> same
        // encoding, different order -> different encoding, and each round-trips to its original.
        val a = SharedStorageScope(listOf("/sdcard/a", "/sdcard/b"))
        val b = SharedStorageScope(listOf("/sdcard/b", "/sdcard/a"))
        assertEquals(UserScopeCodec.encode(a), UserScopeCodec.encode(a))
        assertNotEquals(UserScopeCodec.encode(a), UserScopeCodec.encode(b))
        assertEquals(a, UserScopeCodec.decode(UserScopeCodec.encode(a)))
        assertEquals(b, UserScopeCodec.decode(UserScopeCodec.encode(b)))
    }

    @Test
    fun theDisplayOnlyFieldsThatToScopeRefDropsSurviveTheCodec() {
        // toScopeRef drops the SAF display name and the root-session start time; the codec keeps
        // both, so a rehydrated rule scope still equals the live one.
        val saf = DocumentTreeScope("content://x/tree/y", "Renamed Later")
        assertEquals(saf, UserScopeCodec.decode(UserScopeCodec.encode(saf)))
        val root = RootSessionScope(epoch, epoch.plusSeconds(600), false)
        assertEquals(root, UserScopeCodec.decode(UserScopeCodec.encode(root)))
    }

    @Test
    fun blankOrUnversionedInputDecodesToNull() {
        assertNull(UserScopeCodec.decode(""))
        assertNull(UserScopeCodec.decode("garbage"))
        assertNull(UserScopeCodec.decode("vX${fs}w${fs}ws-1"))
    }

    @Test
    fun anUnknownTagDecodesToNull() {
        assertNull(UserScopeCodec.decode("hsr1${fs}z${fs}9"))
    }

    @Test
    fun aWrongFieldCountDecodesToNull() {
        assertNull(UserScopeCodec.decode("hsr1${fs}w")) // tag but no field
        assertNull(UserScopeCodec.decode("hsr1${fs}w${fs}a${fs}b")) // too many fields for a workspace scope
    }

    @Test
    fun aNonNumericFieldDecodesToNull() {
        assertNull(UserScopeCodec.decode("hsr1${fs}b${fs}tab-1${fs}notanint")) // bad navigation generation
        assertNull(UserScopeCodec.decode("hsr1${fs}a${fs}com.x.app${fs}${fs}oops${fs}1700000000")) // bad maxActions
    }

    @Test
    fun aCorruptedListFieldDecodesToNull() {
        // An empty element inside the All-files root list is malformed storage -> fail closed.
        assertNull(UserScopeCodec.decode("hsr1${fs}f$fs/a${ls}$ls/b"))
    }
}
