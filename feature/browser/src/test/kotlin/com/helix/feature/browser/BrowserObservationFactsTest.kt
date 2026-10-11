package com.helix.feature.browser

import com.helix.core.policy.BrowserTabScope
import com.helix.feature.browser.snapshot.BrowserSnapshot
import com.helix.feature.browser.snapshot.SnapshotNode
import com.helix.feature.browser.snapshot.UntrustedWebContent
import com.helix.tools.browser.BrowserObservationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserObservationFactsTest {
    @Test fun metadataPrefixKeepsUnicodePairsWhole() {
        assertEquals("abc", BrowserObservationFacts.prefix("abc😀", 4))
        assertEquals("abc😀", BrowserObservationFacts.prefix("abc😀", 5))
        assertEquals("", BrowserObservationFacts.prefix("😀", 1))
    }

    @Test fun discoveryRequiresOriginalSessionOrExactCurrentScope() {
        val tab = BrowserTab("tab-a", navigationGeneration = 2, originSessionId = "original")
        assertTrue(BrowserObservationFacts.allowed(tab, BrowserObservationContext("original", null)))
        assertFalse(BrowserObservationFacts.allowed(tab, BrowserObservationContext("other", null)))
        assertFalse(
            BrowserObservationFacts.allowed(
                tab.copy(originSessionId = null),
                BrowserObservationContext("original", null),
            ),
        )
        assertFalse(BrowserObservationFacts.allowed(tab, BrowserObservationContext(null, null)))
        assertTrue(
            BrowserObservationFacts.allowed(
                tab,
                BrowserObservationContext("other", BrowserTabScope(tab.id, 2).toScopeRef()),
            ),
        )
        assertFalse(
            BrowserObservationFacts.allowed(
                tab,
                BrowserObservationContext("original", BrowserTabScope(tab.id, 1).toScopeRef()),
            ),
        )
        assertFalse(BrowserObservationFacts.allowed(tab, BrowserObservationContext("original", "unrecognized-scope")))
    }

    @Test fun matchCountIsNotTheReturnedHitLimitAndTruncationCannotProveUniqueness() {
        val nodes =
            (0..79).map {
                SnapshotNode(
                    it,
                    "button",
                    "button",
                    UntrustedWebContent("Same"),
                    null,
                    null,
                    null,
                    "t$it",
                )
            }
        val snapshot =
            BrowserSnapshot(
                "tab-a",
                UntrustedWebContent("data:"),
                UntrustedWebContent("Fixture"),
                "data:opaque",
                2,
                "fp",
                1000,
                false,
                nodes.size,
                nodes,
            )
        val found = BrowserObservationFacts.find(snapshot, "same", false)
        assertEquals(80, found.matchCount)
        assertEquals(50, found.matches.size)
        assertTrue(found.truncated)
        assertFalse(found.observationExpired)
        assertEquals(1000L, found.observedAtMillis)
        assertTrue(
            BrowserObservationFacts
                .find(
                    snapshot.copy(nodes = nodes.take(1), truncated = true),
                    "same",
                    true,
                ).truncated,
        )
        assertTrue(BrowserObservationFacts.find(snapshot, "missing", true).observationExpired)
    }
}
