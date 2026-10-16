package com.helix.app.plugin

import com.helix.extensions.plugin.PluginEndpoint
import com.helix.extensions.plugin.PluginPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginImportReviewTest {
    private val bundle =
        PluginPackage(
            "sample",
            "CLAUDE",
            "b".repeat(64),
            listOf(PluginEndpoint("main", "https://example.com/mcp", false)),
            emptyList(),
            emptyList(),
        )
    private val canonical = "local:${bundle.source}:${bundle.contentHash}"

    private fun record(
        id: String = "saved",
        identity: String = canonical,
        hash: String = bundle.contentHash,
    ) = InstalledPlugin(
        id,
        bundle.name,
        bundle.source,
        hash,
        bundle.endpoints.map { InstalledEndpoint("endpoint-$id", it) },
        emptyList(),
        emptyList(),
        identity,
        3,
        true,
    )

    @Test fun exactLocalIdentityReusesSavedInstallation() {
        val saved = record()
        val review = PluginImportReview.capture(bundle, listOf(saved))
        assertEquals(saved, review.target)
        assertEquals(saved.identity, review.identity)
        assertTrue(review.alreadyInstalled)
        assertFalse(review.needsChoice)
    }

    @Test fun sameNameRequiresExplicitTargetAndCapturesItsRevision() {
        val first = record("one", "local:first", "old")
        val second = record("two", "local:second", "old")
        val review = PluginImportReview.capture(bundle, listOf(first, second))
        assertTrue(review.needsChoice)
        assertNull(review.target)
        val selected = review.chooseTarget(second.id)
        assertEquals("local:second", selected.identity)
        assertEquals(3L, selected.target?.revision)
        assertFalse(selected.needsChoice)
        assertFalse(selected.alreadyInstalled)
    }

    @Test fun separateInstallDoesNotOverwriteCanonicalIdentityAfterEarlierUpdate() {
        val updated = record(hash = "newer-content")
        val review = PluginImportReview.capture(bundle, listOf(updated), uniqueId = { "unique" })
        assertTrue(review.needsChoice)
        assertEquals("$canonical:unique", review.chooseSeparate().identity)
        assertNull(review.chooseSeparate().target)
        assertFalse(review.chooseSeparate().needsChoice)
    }

    @Test fun namesAndHashesNeverClaimMarketOrNativeInstallations() {
        val market = record(identity = "market:trusted/sample")
        val native = record("native").copy(native = NativePluginComponent("sample", "runtime"))
        val otherFormat = record("different", "local:other").copy(source = "OTHER")
        val review = PluginImportReview.capture(bundle, listOf(market, native, otherFormat))
        assertNull(review.target)
        assertTrue(review.candidates.isEmpty())
        assertEquals(canonical, review.identity)
    }

    @Test fun explicitUpdateKeepsChosenIdentityIncludingMarketInstallation() {
        val market = record(identity = "market:trusted/sample", hash = "old")
        val review = PluginImportReview.capture(bundle, listOf(record(), market), market)
        assertEquals(market, review.target)
        assertEquals(market.identity, review.identity)
    }

    @Test(expected = NoSuchElementException::class)
    fun arbitraryTargetIdCannotBeSelected() {
        PluginImportReview.capture(bundle, emptyList()).chooseTarget("other")
    }

    @Test fun explicitRepreviewRefreshesTargetButCapturedChoiceDoesNot() {
        val saved = record(hash = "old")
        val captured = PluginImportReview.capture(bundle, listOf(saved), saved)
        val newer = saved.copy(hash = "newer", revision = 4)
        val refreshed = PluginImportReview.capture(bundle, listOf(newer), saved)
        assertEquals(3L, captured.target?.revision)
        assertEquals(4L, refreshed.target?.revision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun removedExplicitTargetCannotBecomeANewInstallation() {
        PluginImportReview.capture(bundle, emptyList(), record())
    }
}
