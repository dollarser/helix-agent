package com.helix.feature.files

import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-044: [SafGrantStore] — persisted SAF tree grants (持久化 tree grant + 撤销检测 registry).
 *
 * Axes covered:
 * - grant/lookup/revoke/persistence round-trip with a deterministic, model-legal scope id;
 * - idempotent re-grant (name refreshed, original grant time kept);
 * - a corrupt or wrong-version registry is QUARANTINED (renamed aside, never deleted) and the
 *   store starts empty;
 * - a tampered registry cannot alias a grant onto a foreign scope id (per-entry revalidation);
 * - the revocation sweep removes exactly the grants whose provider no longer answers.
 */
class SafGrantStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val clock = { now }

    private fun store(path: Path): SafGrantStore = SafGrantStore(path, clock)

    private fun storePath(name: String): Path = tmp.newFolder(name).toPath().resolve("saf-grants.json")

    // ── Grant / lookup / revoke ─────────────────────────────────────────────────────────

    @Test
    fun grantPersistsAndReloadsRoundTrip() {
        val path = storePath("roundtrip")
        val a = store(path).grant("content://com.example.documents/tree/alpha", "Docs")
        now = 2_000L
        val b = store(path).grant("content://com.example.documents/tree/beta", "Beta")

        val reloaded = store(path)
        assertEquals(2, reloaded.list().size)
        assertEquals(a, reloaded.find(a.scopeId))
        assertEquals(b, reloaded.find(b.scopeId))
        // Oldest first.
        assertEquals(listOf(a.scopeId, b.scopeId), reloaded.list().map { it.scopeId })
    }

    @Test
    fun reGrantingTheSameTreeIsIdempotentAndKeepsTheOriginalTime() {
        val path = storePath("idem")
        val first = store(path).grant("content://host/tree/1", "Old name")
        now = 5_000L
        val second = store(path).grant("content://host/tree/1", "New name")

        assertEquals(first.scopeId, second.scopeId)
        assertEquals("New name", second.displayName)
        assertEquals("the original grant time must be kept", first.grantedAtMillis, second.grantedAtMillis)
        assertEquals(1, store(path).list().size)
    }

    @Test
    fun revokeRemovesTheGrantAndPersistsIt() {
        val path = storePath("revoke")
        val grant = store(path).grant("content://host/tree/9", "Nine")
        assertTrue(store(path).revoke(grant.scopeId))
        assertFalse(store(path).revoke(grant.scopeId))
        assertTrue(store(path).list().isEmpty())
        assertNull(store(path).find(grant.scopeId))
    }

    @Test
    fun aNonContentUriIsRejected() {
        val store = store(storePath("noncontent"))
        assertThrows(IllegalArgumentException::class.java) { store.grant("file:///etc/passwd", "nope") }
    }

    @Test
    fun derivedScopeIdIsDeterministicAndModelLegal() {
        val store = store(storePath("scopeid"))
        val id = store.deriveScopeId("content://host/tree/x")
        assertEquals(id, store.deriveScopeId("content://host/tree/x"))
        assertTrue(id.startsWith("saf-"))
        assertEquals("prefix (4) + 12 hex chars", 16, id.length)
        assertTrue(id.drop(4).matches(Regex("[0-9a-f]{12}")))
        // A derived id must be a legal FileScopePath scope id (doc 10: 模型只看到 scopeId).
        FileScopePath(id, "input/a.txt")
    }

    // ── Corruption / tampering ──────────────────────────────────────────────────────────

    @Test
    fun aCorruptRegistryIsQuarantinedAndTheStoreStartsEmpty() {
        val path = storePath("corrupt")
        Files.write(path, "this is not json {{{".toByteArray())

        val fresh = store(path)
        assertTrue(fresh.list().isEmpty())
        assertFalse("the corrupt file must not remain under its name", Files.exists(path))
        val quarantined = quarantineFiles(path)
        assertEquals("exactly one quarantined copy", 1, quarantined.size)
        // The quarantine copy is kept verbatim (never deleted).
        assertEquals("this is not json {{{", String(Files.readAllBytes(quarantined.single())))
    }

    @Test
    fun aWrongVersionRegistryIsQuarantined() {
        val path = storePath("version")
        Files.write(path, """{"version":2,"grants":[]}""".toByteArray())
        assertTrue(store(path).list().isEmpty())
        assertFalse(Files.exists(path))
    }

    @Test
    fun aTamperedEntryCannotAliasOntoAForeignScopeId() {
        val path = storePath("tamper")
        val valid = store(path).grant("content://host/tree/valid", "Valid")
        now = 3_000L
        // Hand-craft a registry where one entry claims a scope id that does NOT derive from its
        // own treeUri — a tamper that would alias that tree onto someone else's grant.
        val forgedId = store(path).deriveScopeId("content://host/tree/other")
        val json =
            """{"version":1,"grants":[
                {"scopeId":"${valid.scopeId}","treeUri":"content://host/tree/valid","displayName":"Valid","grantedAtMillis":1000},
                {"scopeId":"$forgedId","treeUri":"content://host/tree/evil",
                 "displayName":"Evil","grantedAtMillis":3000}]}"""
        Files.write(path, json.toByteArray())

        val reloaded = store(path)
        assertEquals(1, reloaded.list().size)
        assertEquals(valid, reloaded.list().single())
        assertNull(reloaded.find(forgedId))
    }

    @Test
    fun aStructurallyMalformedEntryIsDroppedWithoutLosingTheValidOnes() {
        val path = storePath("malformed")
        val valid = store(path).grant("content://host/tree/ok", "OK")
        val json =
            """{"version":1,"grants":[
                {"scopeId":"${valid.scopeId}","treeUri":"content://host/tree/ok","displayName":"OK","grantedAtMillis":1000},
                {"scopeId":"saf-000000000000","treeUri":42,"displayName":null,"grantedAtMillis":"soon"}]}"""
        Files.write(path, json.toByteArray())
        val reloaded = store(path)
        assertEquals(1, reloaded.list().size)
        assertEquals(valid, reloaded.list().single())
    }

    // ── 撤销检测 sweep ──────────────────────────────────────────────────────────────────

    @Test
    fun theSweepRemovesExactlyTheGrantsWhoseProviderNoLongerAnswers() {
        val path = storePath("sweep")
        val alive = store(path).grant("content://host/tree/alive", "Alive")
        now = 4_000L
        val dead = store(path).grant("content://host/tree/dead", "Dead")

        val probe = SafGrantProbe { uri -> uri.endsWith("/alive") }
        val revoked = store(path).sweepRevoked(probe)

        assertEquals(listOf(dead), revoked)
        assertEquals(listOf(alive), store(path).list())
        // The revocation survived a reload.
        assertNull(store(path).find(dead.scopeId))
    }

    @Test
    fun aSweepThatRevokesNothingLeavesTheFileUnchanged() {
        val path = storePath("noop")
        store(path).grant("content://host/tree/only", "Only")
        val before = Files.readAllBytes(path)
        store(path).sweepRevoked(SafGrantProbe { true })
        assertEquals(before.toList(), Files.readAllBytes(path).toList())
    }

    /** The quarantine helper under test moves rather than deletes — this pins that contract. */
    @Test
    fun quarantineKeepsTheOriginalBytesRecoverable() {
        val path = storePath("recover")
        Files.write(path, """{"version":1}garbage""".toByteArray())
        store(path)
        assertEquals(1, quarantineFiles(path).size)
        // And the store is usable afterwards.
        val grant = store(path).grant("content://host/tree/post", "Post")
        assertNotNull(store(path).find(grant.scopeId))
    }

    /** The `.corrupt-` siblings of [storePath] (the quarantine convention under test). */
    private fun quarantineFiles(storePath: Path): List<Path> {
        val found = mutableListOf<Path>()
        Files.list(storePath.parent).use { stream ->
            for (entry in stream) {
                if (entry.fileName.toString().startsWith("saf-grants.json.corrupt-")) found.add(entry)
            }
        }
        return found
    }
}
