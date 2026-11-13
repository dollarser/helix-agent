package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/** Operates only in the caller-created UUID subtree of the explicitly selected system tree. */
internal class SafSystemDirectoryContract(
    private val saf: SafManualFileBackend,
    private val scope: String,
    private val owned: String,
    private val privateRoot: File,
    private val writable: () -> Boolean,
) {
    private val local = File(privateRoot, "local").apply { check(mkdirs()) }
    private val state = File(privateRoot, "sync.json")
    private val nio = NioManualFileBackend("app", ScopeRootResolver { privateRoot.toPath() }, false) {}

    fun verify() {
        verifySearch()
        SafNativeSearchContract(saf, scope, owned).verify()
        verifySync()
    }

    private fun verifySearch() {
        val base = "$owned/search"
        saf.create(base, true)
        saf.create("$base/nested", true)
        put("$base/Match-root.txt", "root")
        put("$base/nested/match-child.txt", "child")
        saf.create("$base/.helix", true)
        put("$base/.helix/match-hidden.txt", "metadata")
        saf.create("$base/nested/.helix", true)
        put("$base/nested/.helix/match-hidden.txt", "nested metadata")
        val store =
            WorkspaceArtifactStore(
                ScopeRootResolver { error("System SAF search must not fall back to a native path") },
                documentBackend = { requested ->
                    require(requested == scope)
                    saf
                },
            )
        val root = FileScopePath(scope, base)
        val result = store.search(root, "MATCH", 2, 3)
        assertEquals(
            listOf("$base/Match-root.txt", "$base/nested/match-child.txt"),
            result.matches.map { it.relativePath },
        )
        assertTrue(result.matches.all { it.scopeId == scope })
        assertFalse(result.truncated)
        val resultLimited = store.search(root, "match", 1, 10)
        assertEquals(listOf("$base/Match-root.txt"), resultLimited.matches.map { it.relativePath })
        assertTrue(resultLimited.truncated)
        val scanLimited = store.search(root, "match", 10, 1)
        assertEquals(listOf("$base/Match-root.txt"), scanLimited.matches.map { it.relativePath })
        assertTrue(scanLimited.truncated)
        val nested = store.search(FileScopePath(scope, "$base/nested"), "match", 1, 1)
        assertEquals(listOf("$base/nested/match-child.txt"), nested.matches.map { it.relativePath })
        assertFalse(nested.truncated)
        assertEquals("metadata", text("$base/.helix/match-hidden.txt"))
        assertEquals("nested metadata", text("$base/nested/.helix/match-hidden.txt"))
    }

    @Suppress("LongMethod") // One baseline crosses both-direction edits, conflict, reopen and explicit resolution.
    private fun verifySync() {
        val remote = "$owned/sync"
        saf.create(remote, true)
        saf.create("$remote/provider", true)
        put("$remote/provider/remote.txt", "provider initial")
        check(File(local, "nested").mkdirs())
        File(local, "nested/shared.txt").writeText("local initial")
        var service = service()
        val id = service.create(SyncRoot("app", "local"), SyncRoot(scope, remote)).id
        service.run(id)
        assertEquals("COMPLETE", service.list().single().status)
        assertEquals("provider initial", File(local, "provider/remote.txt").readText())
        assertEquals("local initial", text("$remote/nested/shared.txt"))
        assertTrue(operations().pendingTransfers().isEmpty())
        File(local, "nested/shared.txt").writeText("local unilateral edit")
        service = service()
        service.run(id)
        assertEquals("COMPLETE", service.list().single().status)
        assertEquals("local unilateral edit", text("$remote/nested/shared.txt"))
        put("$remote/provider/remote.txt", "provider unilateral edit")
        service = service()
        service.run(id)
        assertEquals("COMPLETE", service.list().single().status)
        assertEquals("provider unilateral edit", File(local, "provider/remote.txt").readText())
        File(local, "nested/shared.txt").writeText("local conflicting edit")
        put("$remote/nested/shared.txt", "provider conflicting edit")
        val baseline = service.list().single().base
        service.run(id)
        assertConflict(service)
        assertEquals(baseline, service.list().single().base)
        service = service()
        assertConflict(service)
        assertEquals("local conflicting edit", File(local, "nested/shared.txt").readText())
        assertEquals("provider conflicting edit", text("$remote/nested/shared.txt"))
        service.run(id)
        assertConflict(service)
        assertEquals(baseline, service.list().single().base)
        assertEquals("local conflicting edit", File(local, "nested/shared.txt").readText())
        assertEquals("provider conflicting edit", text("$remote/nested/shared.txt"))
        File(local, "nested/shared.txt").writeText("explicitly reconciled")
        put("$remote/nested/shared.txt", "explicitly reconciled")
        service.run(id)
        assertEquals("COMPLETE", service.list().single().status)
        assertTrue(
            service
                .list()
                .single()
                .conflicts
                .isEmpty(),
        )
        assertEquals(0, service.list().single().copied)
        assertEquals("explicitly reconciled", text("$remote/nested/shared.txt"))
        assertEquals("explicitly reconciled", File(local, "nested/shared.txt").readText())
        assertTrue(operations().pendingTransfers().isEmpty())
        assertEquals("COMPLETE", service().list().single().status)
    }

    private fun assertConflict(service: DirectorySyncService) {
        val rule = service.list().single()
        assertEquals("CONFLICT", rule.status)
        assertEquals(listOf("nested/shared.txt"), rule.conflicts)
        assertFalse(rule.enabled)
        assertEquals(0, rule.copied)
    }

    private fun put(
        path: String,
        value: String,
    ) {
        require(path.startsWith("$owned/"))
        if (saf.stat(path) == null) saf.create(path, false)
        saf.write(path).use { it.write(value.toByteArray()) }
    }

    private fun text(path: String) = saf.read(path).bufferedReader().use { it.readText() }

    private fun operations() =
        ManualFileOperations(
            { requested ->
                when (requested) {
                    scope -> saf
                    "app" -> nio
                    else -> error("Unexpected fixture scope")
                }
            },
            { requested -> requested == "app" || (requested == scope && writable()) },
            ManualTransferJournal(File(privateRoot, "journal").toPath()),
        )

    private fun service() =
        DirectorySyncService(
            operations(),
            { state.takeIf { it.exists() }?.readText() },
            { state.writeText(it) },
            { _, _ -> },
        )

    fun removeOwnedSubtree() {
        require(owned.matches(Regex("owned-[0-9a-f-]{36}")))
        operations().delete(scope, owned)
        assertEquals(null, saf.stat(owned))
    }
}
