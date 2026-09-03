package com.helix.feature.files

import com.helix.core.workspace.ScopeNotAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * HXA-057 (JVM gate): [SafTreeScopeResolver] — the SAF tree scope's real-time re-verification and
 * fail-closed contract. Axes (roadmap): malicious ContentProvider (provider-identity swap), 路径/
 * 文档 ID 欺骗 (aliasing + URI change), 撤销竞态 (provider gone / root gone), 跨 scope, 只读 grant,
 * and grant 泄漏 (no `content://` URI in any model-visible or diagnostic surface).
 */
class SafTreeScopeResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A real store, used ONLY for its deterministic [SafTreeGrantStore.deriveScopeId]. */
    private lateinit var deriveStore: SafGrantStore

    private val treeA = "content://host/tree/ta"
    private val treeB = "content://host/tree/tb"

    @Before
    fun setUp() {
        deriveStore = SafGrantStore(tmp.newFolder("store").toPath().resolve("g.json")) { 0L }
    }

    // A registry the tests control directly, so the tampered / aliasing case can be constructed
    // (the real store drops such entries on load, so a fake is needed to exercise that path).
    private class FakeRegistry(
        private val entries: List<SafTreeGrant>,
        private val derive: (String) -> String,
    ) : SafTreeGrantRegistry {
        override fun find(scopeId: String): SafTreeGrant? = entries.firstOrNull { it.scopeId == scopeId }

        override fun list(): List<SafTreeGrant> = entries

        override fun deriveScopeId(treeUri: String): String = derive(treeUri)
    }

    /** A check the test drives per case; records the URIs it was asked about (跨 scope proof). */
    private class FakeCheck(
        private val result: (String) -> SafTreeGrantFacts?,
    ) : SafTreeGrantCheck {
        val asked = mutableListOf<String>()

        override fun verify(treeUri: String): SafTreeGrantFacts? {
            asked += treeUri
            return result(treeUri)
        }
    }

    private fun facts(
        rootLive: Boolean = true,
        authority: String? = null,
        rootDocumentId: String? = null,
        readable: Boolean = true,
        writable: Boolean = true,
    ): SafTreeGrantFacts {
        val a = authority ?: treeUriAuthority(treeA)
        val r = rootDocumentId ?: treeUriRootDocumentId(treeA)
        return SafTreeGrantFacts(rootLive, a, r, readable, writable)
    }

    private fun grant(
        scopeId: String,
        treeUri: String,
    ): SafTreeGrant = SafTreeGrant(scopeId, treeUri, "Docs", 0L)

    /** A registry whose [deriveScopeId] is the store's real algorithm (so aliasing checks are exact). */
    private fun registry(vararg grants: SafTreeGrant): FakeRegistry =
        FakeRegistry(grants.toList()) { deriveStore.deriveScopeId(it) }

    // ── Happy path ───────────────────────────────────────────────────────────────────────

    @Test
    fun aLiveReadWriteGrantResolvesForBothModes() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        val resolver = SafTreeScopeResolver(registry(grant(scopeId, treeA)), FakeCheck { facts() })
        val read = resolver.resolve(scopeId, SafAccessMode.READ)
        val write = resolver.resolve(scopeId, SafAccessMode.WRITE)
        assertEquals(scopeId, read.scopeId)
        assertEquals("Docs", read.displayName)
        assertTrue(read.readable)
        assertTrue(write.writable)
    }

    // ── 撤销竞态: provider gone / root gone ───────────────────────────────────────────────

    @Test
    fun aRevokedOrGoneProviderFailsClosedEvenThoughTheGrantIsStored() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        // The grant is in the registry, but the provider no longer answers (check → null).
        val resolver = SafTreeScopeResolver(registry(grant(scopeId, treeA)), FakeCheck { null })
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeId, SafAccessMode.READ) }
        assertFalse("a revoked grant must not surface as live", resolver.liveScope(scopeId) != null)
    }

    @Test
    fun aGoneRootDocumentFailsClosed() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        val resolver =
            SafTreeScopeResolver(
                registry(grant(scopeId, treeA)),
                FakeCheck { facts(rootLive = false) },
            )
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeId, SafAccessMode.READ) }
    }

    // ── 恶意 ContentProvider: provider identity (authority) swap ──────────────────────────

    @Test
    fun aProviderNowAnsweringUnderADifferentAuthorityFailsClosed() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        val resolver =
            SafTreeScopeResolver(
                registry(grant(scopeId, treeA)),
                FakeCheck { facts(authority = "com.evil.provider") },
            )
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeId, SafAccessMode.READ) }
    }

    // ── 路径/文档 ID 欺骗: aliasing + URI change ──────────────────────────────────────────

    @Test
    fun aTamperedScopeIdAliasedOntoaForeignTreeFailsClosed() {
        // scopeId = derive(treeA) but the stored treeUri is treeB → derive(treeB) != scopeId.
        val scopeA = deriveStore.deriveScopeId(treeA)
        val resolver =
            SafTreeScopeResolver(
                registry(grant(scopeA, treeB)),
                FakeCheck { facts() },
            )
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeA, SafAccessMode.READ) }
    }

    @Test
    fun aRootDocumentIdThatChangedSinceTheGrantFailsClosed() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        val resolver =
            SafTreeScopeResolver(
                registry(grant(scopeId, treeA)),
                FakeCheck { facts(rootDocumentId = "some-other-root") },
            )
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeId, SafAccessMode.READ) }
    }

    // ── 只读 grant ────────────────────────────────────────────────────────────────────────

    @Test
    fun aReadOnlyGrantRefusesAWriteOperation() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        val resolver =
            SafTreeScopeResolver(
                registry(grant(scopeId, treeA)),
                FakeCheck { facts(writable = false) },
            )
        assertEquals(scopeId, resolver.resolve(scopeId, SafAccessMode.READ).scopeId)
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeId, SafAccessMode.WRITE) }
    }

    @Test
    fun anUnreadableGrantRefusesAReadOperation() {
        val scopeId = deriveStore.deriveScopeId(treeA)
        val resolver =
            SafTreeScopeResolver(
                registry(grant(scopeId, treeA)),
                FakeCheck { facts(readable = false) },
            )
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve(scopeId, SafAccessMode.READ) }
    }

    // ── 跨 scope: a path referencing scope A resolves only against A's tree ──────────────

    @Test
    fun aCrossScopeReferenceResolvesOnlyAgainstItsOwnTree() {
        val scopeA = deriveStore.deriveScopeId(treeA)
        val scopeB = deriveStore.deriveScopeId(treeB)
        val reg = registry(grant(scopeA, treeA), grant(scopeB, treeB))
        // A live check that answers each tree with ITS OWN identity (so both scopes resolve).
        val check =
            FakeCheck { uri ->
                SafTreeGrantFacts(true, treeUriAuthority(uri), treeUriRootDocumentId(uri), true, true)
            }
        val resolver = SafTreeScopeResolver(reg, check)
        // Resolve scope A: the check must be asked about treeA, never treeB.
        resolver.resolve(scopeA, SafAccessMode.READ)
        assertEquals(listOf(treeA), check.asked)
        // And scope B is an independent grant resolved against treeB.
        resolver.resolve(scopeB, SafAccessMode.READ)
        assertEquals(listOf(treeA, treeB), check.asked)
    }

    // ── unknown scope ────────────────────────────────────────────────────────────────────

    @Test
    fun anUnknownScopeFailsClosed() {
        val resolver = SafTreeScopeResolver(registry(), FakeCheck { facts() })
        assertThrows(ScopeNotAvailable::class.java) { resolver.resolve("saf-000000000000", SafAccessMode.READ) }
    }

    // ── grant 泄漏: no content:// URI in any model-visible / diagnostic surface ──────────

    @Test
    fun noRawUriLeaksIntoScopeMessagesOrLiveSources() {
        val scopeA = deriveStore.deriveScopeId(treeA)
        val reg = registry(grant(scopeA, treeA))
        // Collect every failure message across every failure mode.
        val failingChecks =
            listOf(
                FakeCheck { null },
                FakeCheck { facts(rootLive = false) },
                FakeCheck { facts(authority = "com.evil") },
                FakeCheck { facts(rootDocumentId = "other") },
                FakeCheck { facts(readable = false) },
            )
        val messages =
            failingChecks
                .mapNotNull { check ->
                    runCatching { SafTreeScopeResolver(reg, check).resolve(scopeA, SafAccessMode.READ) }
                        .exceptionOrNull()
                        ?.message
                }.toMutableList()
        // A tampered-registry aliasing message too.
        messages +=
            runCatching {
                SafTreeScopeResolver(registry(grant(scopeA, treeB)), FakeCheck { facts() })
                    .resolve(scopeA, SafAccessMode.READ)
            }.exceptionOrNull()?.message.orEmpty()

        for (message in messages) {
            assertFalse("failure message must not leak the URI: $message", message.contains("content://"))
            assertFalse("failure message must not leak the tree: $message", message.contains(treeA))
            assertFalse("failure message must not leak the tree: $message", message.contains(treeB))
        }

        // The model-opaque scope carries only scopeId + name.
        val resolver = SafTreeScopeResolver(reg, FakeCheck { facts() })
        val scope = resolver.resolve(scopeA, SafAccessMode.READ)
        val modelRef = scope.toModelReference()
        assertFalse(modelRef.contains("content://"))
        assertFalse(modelRef.contains(treeA))
        assertTrue(modelRef.startsWith("scope:$scopeA:"))

        // liveSources entries carry only scopeId + name.
        val live = resolver.liveScope(scopeA)!!
        assertFalse(live.scopeId.contains("content://"))
    }

    // ── uri helper edge cases (malformed / non-content URIs fail closed upstream) ────────

    @Test
    fun theUriHelpersParseAuthorityAndRootDocumentId() {
        assertEquals("host", treeUriAuthority("content://host/tree/xyz"))
        assertEquals("xyz", treeUriRootDocumentId("content://host/tree/xyz"))
        assertEquals("b", treeUriRootDocumentId("content://host/tree/a/b"))
        val safDocUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        assertEquals("com.android.externalstorage.documents", treeUriAuthority(safDocUri))
        assertEquals("primary%3ADocuments", treeUriRootDocumentId(safDocUri))
        assertEquals(null, treeUriAuthority("file:///etc/passwd"))
        assertEquals(null, treeUriRootDocumentId("file:///etc/passwd"))
    }
}
