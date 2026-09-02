package com.helix.feature.files.allfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-045: [AllFilesRootsStore] — the persisted registry of enabled all-files roots.
 *
 * Mirrors the SAF grant store's guarantees (atomic JSON, corruption quarantine, per-entry
 * revalidation) and adds the all-files-specific axes: enabling requires a CLOSED-catalog key and
 * an absolute real path (an arbitrary path can never be registered), re-enabling re-resolves the
 * path while keeping the original enable time, and `resolveScopeRoot` returns the real path for a
 * scope id (the seam the app's scope resolver consumes).
 */
class AllFilesRootsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val clock = { now }

    private fun store(path: Path): AllFilesRootsStore = AllFilesRootsStore(path, clock)

    private fun storePath(name: String): Path = tmp.newFolder(name).toPath().resolve("all-files-roots.json")

    // ── Enable / lookup / disable ───────────────────────────────────────────────────────

    @Test
    fun enablePersistsAndReloadsRoundTrip() {
        val path = storePath("roundtrip")
        val a = store(path).enable("download", "/storage/emulated/0/Download")
        now = 2_000L
        val b = store(path).enable("documents", "/storage/emulated/0/Documents")

        val reloaded = store(path)
        assertEquals(2, reloaded.list().size)
        assertEquals(a, reloaded.find(a.scopeId))
        assertEquals(b, reloaded.find(b.scopeId))
        // Oldest first.
        assertEquals(listOf(a.scopeId, b.scopeId), reloaded.list().map { it.scopeId })
    }

    @Test
    fun reEnablingReResolvesThePathButKeepsTheOriginalTime() {
        val path = storePath("idempotent")
        val first = store(path).enable("download", "/storage/emulated/0/Download")
        now = 5_000L
        val second = store(path).enable("download", "/storage/emulated/0/Download")

        assertEquals(first.scopeId, second.scopeId)
        assertEquals("original enable time must be kept", first.enabledAtMillis, second.enabledAtMillis)
        assertEquals(1, store(path).list().size)
    }

    @Test
    fun disableRemovesTheRootAndPersistsIt() {
        val path = storePath("disable")
        val grant = store(path).enable("pictures", "/storage/emulated/0/Pictures")
        assertTrue(store(path).disable("pictures"))
        assertFalse("a second disable is a no-op", store(path).disable("pictures"))
        assertTrue(store(path).list().isEmpty())
        assertNull(store(path).find(grant.scopeId))
        assertFalse(store(path).isEnabled("pictures"))
    }

    @Test
    fun anUnknownCatalogKeyIsRefused() {
        val store = store(storePath("unknownkey"))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { store.enable("/sdcard/Android/data", "/sdcard/Android/data") }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { store.enable("af-download", "/storage/emulated/0/Download") }
    }

    @Test
    fun aNonAbsolutePathIsRefused() {
        val store = store(storePath("relativepath"))
        assertThrows(IllegalArgumentException::class.java) { store.enable("download", "Download") }
    }

    @Test
    fun resolveScopeRootReturnsTheRealPathOnlyForAnEnabledRoot() {
        val path = storePath("resolveroot")
        val grant = store(path).enable("music", "/storage/emulated/0/Music")
        assertEquals(Path.of("/storage/emulated/0/Music"), store(path).resolveScopeRoot(grant.scopeId))
        assertNull("a disabled root has no resolvable scope", store(path).resolveScopeRoot("af-movies"))
    }

    @Test
    fun theScopeIdIsAFileScopePathScopeId() {
        val grant = store(storePath("scopeid")).enable("dcim", "/storage/emulated/0/DCIM")
        assertTrue(grant.scopeId.startsWith("af-"))
        // Legal as a scope id (doc 10: 模型只看到 scopeId).
        com.helix.core.workspace
            .FileScopePath(grant.scopeId, "input/a.txt")
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
        Files.write(path, """{"version":2,"roots":[]}""".toByteArray())
        assertTrue(store(path).list().isEmpty())
        assertFalse(Files.exists(path))
    }

    @Test
    fun aTamperedEntryCannotAliasOntoAForeignScopeId() {
        val path = storePath("tamper")
        val valid = store(path).enable("download", "/storage/emulated/0/Download")
        // Hand-craft a registry where a "documents" entry claims the scope id of "download" — a
        // tamper that would alias the documents root onto the download grant.
        val json =
            """{"version":1,"roots":[
                {"key":"download","scopeId":"${valid.scopeId}",
                 "realPath":"/storage/emulated/0/Download","enabledAtMillis":1000},
                {"key":"documents","scopeId":"${valid.scopeId}",
                 "realPath":"/storage/emulated/0/Evil","enabledAtMillis":3000}]}"""
        Files.write(path, json.toByteArray())

        val reloaded = store(path)
        assertEquals("only the untampered entry survives", 1, reloaded.list().size)
        assertEquals(valid, reloaded.list().single())
        // The aliased real path must not be reachable under the download scope id.
        assertEquals(valid.realPath, reloaded.resolveScopeRoot(valid.scopeId)?.toString())
    }

    @Test
    fun aStructurallyMalformedEntryIsDroppedWithoutLosingTheValidOnes() {
        val path = storePath("malformed")
        val valid = store(path).enable("movies", "/storage/emulated/0/Movies")
        val json =
            """{"version":1,"roots":[
                {"key":"movies","scopeId":"${valid.scopeId}",
                 "realPath":"/storage/emulated/0/Movies","enabledAtMillis":1000},
                {"key":"podcasts","scopeId":"af-podcasts","realPath":42,"enabledAtMillis":"soon"}]}"""
        Files.write(path, json.toByteArray())
        val reloaded = store(path)
        assertEquals(1, reloaded.list().size)
        assertEquals(valid, reloaded.list().single())
    }

    /** The `.corrupt-` siblings of [storePath] (the quarantine convention under test). */
    private fun quarantineFiles(storePath: Path): List<Path> {
        val found = mutableListOf<Path>()
        Files.list(storePath.parent).use { stream ->
            for (entry in stream) {
                if (entry.fileName.toString().startsWith("all-files-roots.json.corrupt-")) found.add(entry)
            }
        }
        return found
    }
}
