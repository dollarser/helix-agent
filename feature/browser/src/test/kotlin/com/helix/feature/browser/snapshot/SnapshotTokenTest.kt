package com.helix.feature.browser.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotTokenTest {
    private fun baseline(now: Long = 1_000_000L): NodeToken =
        NodeToken(
            version = SnapshotToken.TOKEN_VERSION,
            nodeIndex = 7,
            tabId = "tab-1",
            origin = "https://helix.example",
            navigationGeneration = 3,
            fingerprint = "abc123",
            mintedAtMillis = now,
            ttlMillis = SnapshotToken.DEFAULT_TTL_MILLIS,
        )

    private fun live(token: NodeToken): LiveTabState =
        LiveTabState(
            tabId = token.tabId,
            origin = token.origin,
            navigationGeneration = token.navigationGeneration,
            lastSnapshotFingerprint = token.fingerprint,
        )

    private fun parsed(token: NodeToken): NodeToken {
        val minted = SnapshotToken.parse(SnapshotToken.mint(token))
        assertNotNull(minted)
        return minted!!
    }

    @Test
    fun mintThenParseRoundTripsEveryBinding() {
        val token = baseline()
        assertEquals(token, parsed(token))
    }

    @Test
    fun mintIsDeterministic() {
        val token = baseline()
        assertEquals(SnapshotToken.mint(token), SnapshotToken.mint(token))
    }

    @Test
    fun aFreshTokenAgainstItsOwnTabIsValid() {
        val token = baseline()
        assertSame(TokenVerdict.Valid, SnapshotToken.validate(parsed(token), live(token), 1_000_000L))
    }

    @Test
    fun aTokenThatOutlivesItsTtlIsExpired() {
        val token = baseline(now = 1_000_000L)
        val at = parsed(token)
        // exactly at the TTL boundary (now - minted == ttl) is still valid
        assertSame(
            TokenVerdict.Valid,
            SnapshotToken.validate(at, live(token), 1_000_000L + SnapshotToken.DEFAULT_TTL_MILLIS),
        )
        // one millisecond past it is expired
        assertSame(
            TokenVerdict.Expired,
            SnapshotToken.validate(at, live(token), 1_000_000L + SnapshotToken.DEFAULT_TTL_MILLIS + 1),
        )
    }

    @Test
    fun aTokenBoundToADifferentTabIsWrongTab() {
        val token = baseline()
        val otherTab = live(token).copy(tabId = "tab-2")
        assertSame(TokenVerdict.WrongTab, SnapshotToken.validate(parsed(token), otherTab, 1_000_000L))
    }

    @Test
    fun aTokenAfterACrossOriginNavigationIsStaleOrigin() {
        val token = baseline()
        val moved = live(token).copy(origin = "https://other.example")
        assertSame(TokenVerdict.StaleOrigin, SnapshotToken.validate(parsed(token), moved, 1_000_000L))
    }

    @Test
    fun aTokenAfterANewerNavigationIsStaleGeneration() {
        val token = baseline()
        val bumped = live(token).copy(navigationGeneration = 4)
        assertSame(TokenVerdict.StaleGeneration, SnapshotToken.validate(parsed(token), bumped, 1_000_000L))
    }

    @Test
    fun aTokenWhoseFingerprintNoLongerMatchesIsStaleFingerprint() {
        val token = baseline()
        val reSnapshot = live(token).copy(lastSnapshotFingerprint = "changed")
        assertSame(TokenVerdict.StaleFingerprint, SnapshotToken.validate(parsed(token), reSnapshot, 1_000_000L))
    }

    @Test
    fun aTokenWithNoLiveSnapshotIsStaleFingerprint() {
        val token = baseline()
        val noSnapshot = live(token).copy(lastSnapshotFingerprint = null)
        assertSame(TokenVerdict.StaleFingerprint, SnapshotToken.validate(parsed(token), noSnapshot, 1_000_000L))
    }

    @Test
    fun checkOrderIsTabOriginGenerationFingerprintTtl() {
        val token = baseline()
        // several bindings wrong at once: the FIRST (by binding order) failure wins.
        val allWrong =
            live(token).copy(tabId = "x", origin = "y", navigationGeneration = 9, lastSnapshotFingerprint = "z")
        assertSame(TokenVerdict.WrongTab, SnapshotToken.validate(parsed(token), allWrong, 9_999_999L))
    }

    @Test
    fun malformedTokensDoNotParse() {
        assertNull(SnapshotToken.parse("not-base64!!!"))
        assertNull(
            SnapshotToken.parse(
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("not json".toByteArray()),
            ),
        )
        assertNull(
            SnapshotToken.parse(
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("[1,2,3]".toByteArray()),
            ),
        )
        // a valid JSON object but missing fields
        assertNull(
            SnapshotToken.parse(
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"version\":1}".toByteArray()),
            ),
        )
        // a wrong token version
        val wrongVersion =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                """
                {"version":99,"nodeIndex":0,"tabId":"t","origin":"o",
                "navigationGeneration":1,"fingerprint":"f","mintedAtMillis":1,"ttlMillis":10}
                """.trimIndent()
                    .toByteArray(),
            )
        assertNull(SnapshotToken.parse(wrongVersion))
    }
}
