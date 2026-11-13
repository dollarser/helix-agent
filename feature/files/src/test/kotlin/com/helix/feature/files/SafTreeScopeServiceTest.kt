package com.helix.feature.files

import com.helix.core.workspace.ScopeNotAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

/**
 * HXA-057 (JVM gate): [SafTreeScopeService] — the persisted grant registry + real-time
 * re-verification seam the app consumes. Axes: the source list re-verifies on every call (a dead
 * grant is omitted, never offered); grant/revoke round-trip; the 撤销检测 sweep; **process death**
 * (a fresh store reloaded from disk re-resolves only when the grant still answers); and grant 泄漏
 * (no `content://` URI in any model-visible surface).
 */
class SafTreeScopeServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val clock: () -> Long = { now }

    private fun storePath(name: String): java.nio.file.Path = tmp.newFolder(name).toPath().resolve("saf-grants.json")

    private fun aliveFacts(treeUri: String): SafTreeGrantFacts =
        SafTreeGrantFacts(true, treeUriAuthority(treeUri), treeUriRootDocumentId(treeUri), true, false)

    private fun liveCheck(vararg liveTrees: String): SafTreeGrantCheck =
        SafTreeGrantCheck { uri -> if (uri in liveTrees) aliveFacts(uri) else null }

    // ── grant / resolve / revoke round-trip ──────────────────────────────────────────────

    @Test
    fun grantResolveAndRevokeRoundTrip() {
        val store = SafGrantStore(storePath("rt"), clock)
        val service = SafTreeScopeService(store, liveCheck("content://host/tree/one"))
        val grant = service.grant("content://host/tree/one", "Docs")
        val resolved = service.resolve(grant.scopeId, SafAccessMode.READ)
        assertEquals(grant.scopeId, resolved.scopeId)
        // A read-only grant (the live check reports writable=false) refuses a write.
        assertThrows(ScopeNotAvailable::class.java) { service.resolve(grant.scopeId, SafAccessMode.WRITE) }
        assertTrue(service.revoke(grant.scopeId))
        assertFalse(service.revoke(grant.scopeId))
        assertNull(service.source(grant.scopeId))
    }

    @Test
    fun removalReleasesOnlyTheSelectedTreeBeforeForgettingIt() {
        val path = storePath("release")
        val store = SafGrantStore(path, clock)
        val selected = "content://host/tree/selected"
        val other = "content://host/tree/other"
        val released = mutableListOf<String>()
        val service =
            SafTreeScopeService(store, liveCheck(selected, other)) { uri ->
                assertEquals(uri, store.find(store.deriveScopeId(uri))?.treeUri)
                released += uri
            }
        val source = service.grant(selected, "Selected")
        service.grant(other, "Other")
        assertTrue(service.revoke(source.scopeId))
        assertFalse(service.revoke(source.scopeId))
        assertFalse(service.revoke("unknown"))
        assertEquals(listOf(selected), released)
        assertEquals(listOf(other), SafGrantStore(path, clock).list().map { it.treeUri })
        assertThrows(ScopeNotAvailable::class.java) { service.resolve(source.scopeId) }
    }

    @Test
    fun permissionReleaseFailureRetainsTheSourceForRetry() {
        val path = storePath("release-failure")
        val tree = "content://host/tree/retry"
        var failRelease = true
        val service =
            SafTreeScopeService(SafGrantStore(path, clock), liveCheck(tree)) {
                if (failRelease) throw SecurityException("Release refused")
            }
        val source = service.grant(tree, "Retry")
        assertThrows(SecurityException::class.java) { service.revoke(source.scopeId) }
        assertEquals(source.scopeId, service.source(source.scopeId)?.scopeId)
        assertEquals(source, SafGrantStore(path, clock).find(source.scopeId))
        failRelease = false
        assertTrue(service.revoke(source.scopeId))
        assertTrue(SafGrantStore(path, clock).list().isEmpty())
    }

    @Test
    fun persistenceFailureAfterReleaseKeepsIdentityForAnIdempotentRetry() {
        val path = storePath("remove-persistence")
        val tree = "content://host/tree/retry-persistence"
        val permissions = mutableSetOf(tree)
        val service =
            SafTreeScopeService(SafGrantStore(path, clock), liveCheck(tree)) { permissions.remove(it) }
        val source = service.grant(tree, "Retry")
        val saved = Files.readAllBytes(path)
        Files.delete(path)
        Files.createDirectory(path)
        val blocker = Files.createFile(path.resolve("blocker"))
        assertThrows(IOException::class.java) { service.revoke(source.scopeId) }
        assertTrue(permissions.isEmpty())
        assertEquals(source.scopeId, service.source(source.scopeId)?.scopeId)
        Files.delete(blocker)
        Files.delete(path)
        Files.write(path, saved)
        assertTrue(service.revoke(source.scopeId))
        assertTrue(SafGrantStore(path, clock).list().isEmpty())
    }

    // ── 来源列表: re-verifies on every call, omits dead grants ────────────────────────────

    @Test
    fun liveSourcesOnlyListGrantsThatStillAnswer() {
        val store = SafGrantStore(storePath("src"), clock)
        val live = "content://host/tree/live"
        val dead = "content://host/tree/dead"
        store.grant(live, "Live")
        store.grant(dead, "Dead")

        val service = SafTreeScopeService(store, liveCheck(live)) // only `live` still answers
        val sources = service.liveSources()
        assertEquals(1, sources.size)
        assertEquals(store.deriveScopeId(live), sources.single().scopeId)
        val leak = sources.any { it.displayName == "content://host/tree/dead" }
        assertFalse("a dead grant's URI must not appear in the source list", leak)
    }

    // ── 撤销检测 sweep ────────────────────────────────────────────────────────────────────

    @Test
    fun sweepRevokedPersistsOnlyTheGrantsThatNoLongerAnswer() {
        val store = SafGrantStore(storePath("sweep"), clock)
        val live = "content://host/tree/live"
        val dead = "content://host/tree/dead"
        val liveGrant = store.grant(live, "Live")
        val deadGrant = store.grant(dead, "Dead")

        val service = SafTreeScopeService(store, liveCheck(live))
        val revoked = service.sweepRevoked()

        assertEquals(listOf(deadGrant.scopeId), revoked.map { it.scopeId })
        assertNull("the dead grant must be removed and persisted", store.find(deadGrant.scopeId))
        assertEquals("the live grant must be kept", liveGrant, store.find(liveGrant.scopeId))
    }

    // ── 进程死亡 / 重启: a fresh store reloaded from disk ─────────────────────────────────

    @Test
    fun afterAProcessRestartAGrantResolvesOnlyWhenItStillAnswers() {
        val path = storePath("restart")
        val tree = "content://host/tree/persisted"
        SafGrantStore(path, clock).grant(tree, "Persisted")

        // Simulate the process restarting: a FRESH store instance reloads the registry from disk.
        // When the grant still answers, it resolves.
        val liveService = SafTreeScopeService(SafGrantStore(path, clock), liveCheck(tree))
        val scopeId = liveService.knownScopeIds().single()
        assertEquals(scopeId, liveService.resolve(scopeId, SafAccessMode.READ).scopeId)

        // When the grant no longer answers after restart (revoked / provider gone), it fails closed.
        val deadService = SafTreeScopeService(SafGrantStore(path, clock), liveCheck())
        assertThrows(ScopeNotAvailable::class.java) { deadService.resolve(scopeId, SafAccessMode.READ) }
        assertTrue(deadService.liveSources().isEmpty())
    }

    @Test
    fun explicitRegrantAfterRevocationRestoresOnlyTheSameSelectedScope() {
        val path = storePath("regrant")
        val tree = "content://host/tree/selected"
        var accessible = true
        val check =
            SafTreeGrantCheck { uri ->
                if (accessible && uri == tree) aliveFacts(uri).copy(writable = true) else null
            }
        val service = SafTreeScopeService(SafGrantStore(path, clock), check)
        val original = service.grant(tree, "Selected")
        accessible = false
        assertThrows(ScopeNotAvailable::class.java) { service.resolve(original.scopeId, SafAccessMode.READ) }
        assertEquals(listOf(original.scopeId), service.sweepRevoked().map { it.scopeId })
        accessible = true // The fixture models a fresh OS grant, not app-created authority.
        val renewed = service.grant(tree, "Selected again")
        assertEquals(original.scopeId, renewed.scopeId)
        val reopened = SafTreeScopeService(SafGrantStore(path, clock), check)
        assertEquals(original.scopeId, reopened.resolve(renewed.scopeId, SafAccessMode.WRITE).scopeId)
        assertEquals(1, reopened.liveSources().size)
        assertThrows(ScopeNotAvailable::class.java) { reopened.resolve("unselected", SafAccessMode.READ) }
    }

    // ── grant 泄漏: no content:// URI in any model-visible surface ───────────────────────

    @Test
    fun noRawUriLeaksThroughAnyModelVisibleSurface() {
        val store = SafGrantStore(storePath("leak"), clock)
        val tree = "content://host/tree/secret"
        val service = SafTreeScopeService(store, liveCheck(tree))
        val grant = service.grant(tree, "Secret")

        val surfaces =
            listOf(
                grant.scopeId,
                service.source(grant.scopeId)?.displayName.orEmpty(),
                service.liveSources().joinToString { it.displayName + it.scopeId },
                service.knownScopeIds().joinToString(),
                runCatching { service.resolve("saf-000000000000", SafAccessMode.READ) }
                    .exceptionOrNull()
                    ?.message
                    .orEmpty(),
            )
        for (surface in surfaces) {
            assertFalse("model-visible surface must not leak the URI: $surface", surface.contains("content://"))
            assertFalse("model-visible surface must not leak the tree: $surface", surface.contains(tree))
        }
    }
}
