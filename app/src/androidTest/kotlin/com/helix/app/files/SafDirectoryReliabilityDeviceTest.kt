package com.helix.app.files

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeScopeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Real ContentResolver IPC to a test-only DocumentsContract provider, not the system provider.
 * Grant facts are injected; OS persisted-grant/picker coverage belongs to WorkspaceSystemSafDeviceTest.
 */
class SafDirectoryReliabilityDeviceTest {
    @Test fun nestedSearchHonorsResultAndScanLimitsAndHidesMetadata() =
        withFixture { f ->
            f.saf.create("nested", true)
            f.put("Match-root.txt", "root")
            f.put("nested/match-child.txt", "child")
            f.saf.create(".helix", true)
            f.put(".helix/match-secret.txt", "metadata")
            f.saf.create("nested/.helix", true)
            f.put("nested/.helix/match-hidden.txt", "nested metadata")
            val store = f.store()
            val base = FileScopePath(f.scope, "")
            val all = store.search(base, "MATCH", 10, 10)
            assertEquals(setOf("Match-root.txt", "nested/match-child.txt"), all.matches.map { it.relativePath }.toSet())
            assertTrue(all.matches.all { it.scopeId == f.scope })
            assertFalse(all.truncated)
            val exactLimits = store.search(base, "match", 2, 3)
            assertEquals(all.matches, exactLimits.matches)
            assertFalse(exactLimits.truncated)
            val limited = store.search(base, "match", 1, 10)
            assertEquals(listOf("Match-root.txt"), limited.matches.map { it.relativePath })
            assertTrue(limited.truncated)
            val scanned = store.search(base, "match", 10, 1)
            assertEquals(listOf("Match-root.txt"), scanned.matches.map { it.relativePath })
            assertTrue(scanned.truncated)
            val nested = store.search(FileScopePath(f.scope, "nested"), "match", 10, 10)
            assertEquals(listOf("nested/match-child.txt"), nested.matches.map { it.relativePath })
            assertFalse(nested.truncated)
            f.live = false
            assertThrows(Exception::class.java) { store.search(base, "match", 10, 10) }
            f.live = true
            assertEquals("metadata", f.text(".helix/match-secret.txt"))
        }

    @Test fun bidirectionalAdditionsAndSingleSideEditsUseVerifiedProviderBytes() =
        withFixture { f ->
            File(f.local, "nested").mkdirs()
            File(f.local, "nested/local.txt").writeText("from local")
            f.saf.create("remote", true)
            f.put("remote/provider.txt", "from provider")
            var service = f.service()
            val rule = service.create(SyncRoot("app", "local"), SyncRoot(f.scope, ""))
            service.run(rule.id)
            assertEquals("COMPLETE", service.list().single().status)
            assertEquals("from local", f.text("nested/local.txt"))
            assertEquals("from provider", File(f.local, "remote/provider.txt").readText())
            assertTrue(f.operations().pendingTransfers().isEmpty())
            File(f.local, "nested/local.txt").writeText("local edit")
            service = f.service()
            service.run(rule.id)
            assertEquals("COMPLETE", service.list().single().status)
            assertEquals("local edit", f.text("nested/local.txt"))
            f.put("remote/provider.txt", "provider edit")
            service = f.service()
            service.run(rule.id)
            assertEquals("COMPLETE", service.list().single().status)
            assertEquals("provider edit", File(f.local, "remote/provider.txt").readText())
            assertTrue(f.operations().pendingTransfers().isEmpty())
        }

    @Test fun doubleEditConflictSurvivesReopenUntilExplicitContentReconciliation() =
        withFixture { f ->
            File(f.local, "shared.txt").writeText("baseline")
            var service = f.service()
            val id = service.create(SyncRoot("app", "local"), SyncRoot(f.scope, "")).id
            service.run(id)
            assertEquals("COMPLETE", service.list().single().status)
            File(f.local, "shared.txt").writeText("local edit")
            f.put("shared.txt", "remote edit")
            val baseline = service.list().single().base
            service.run(id)
            assertConflict(service, "shared.txt")
            assertEquals(baseline, service.list().single().base)
            service = f.service()
            assertConflict(service, "shared.txt")
            assertEquals("local edit", File(f.local, "shared.txt").readText())
            assertEquals("remote edit", f.text("shared.txt"))
            service.run(id)
            assertConflict(service, "shared.txt")
            assertEquals(baseline, service.list().single().base)
            assertEquals("local edit", File(f.local, "shared.txt").readText())
            assertEquals("remote edit", f.text("shared.txt"))
            File(f.local, "shared.txt").writeText("explicit resolution")
            f.put("shared.txt", "explicit resolution")
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
            assertEquals("explicit resolution", f.text("shared.txt"))
            assertEquals(
                "COMPLETE",
                f
                    .service()
                    .list()
                    .single()
                    .status,
            )
        }

    @Test fun oneSidedDeletionAndTypeReplacementNeverDestroyTheOtherSide() =
        withFixture { f ->
            File(f.local, "deleted.txt").writeText("preserve remote")
            File(f.local, "retyped.txt").writeText("preserve local")
            val service = f.service()
            val id = service.create(SyncRoot("app", "local"), SyncRoot(f.scope, "")).id
            service.run(id)
            assertEquals("COMPLETE", service.list().single().status)
            assertTrue(File(f.local, "deleted.txt").delete())
            f.saf.delete("retyped.txt")
            f.saf.create("retyped.txt", true)
            f.put("retyped.txt/child.txt", "preserve new child")
            repeat(2) {
                val reopened = f.service()
                reopened.run(id)
                assertEquals("CONFLICT", reopened.list().single().status)
                assertTrue(
                    reopened
                        .list()
                        .single()
                        .conflicts
                        .containsAll(listOf("deleted.txt", "retyped.txt")),
                )
                assertEquals(0, reopened.list().single().copied)
                assertFalse(File(f.local, "deleted.txt").exists())
                assertEquals("preserve remote", f.text("deleted.txt"))
                assertEquals("preserve local", File(f.local, "retyped.txt").readText())
                assertTrue(f.saf.stat("retyped.txt")!!.directory)
                assertEquals("preserve new child", f.text("retyped.txt/child.txt"))
            }
        }

    @Test fun revokedGrantStopsSyncAndReauthorizationResumesFromSavedBaseline() =
        withFixture { f ->
            File(f.local, "shared.txt").writeText("baseline")
            val service = f.service()
            val id = service.create(SyncRoot("app", "local"), SyncRoot(f.scope, "")).id
            service.run(id)
            assertEquals("COMPLETE", service.list().single().status)
            val baseline = service.list().single().base
            File(f.local, "shared.txt").writeText("authorized edit")
            f.live = false
            service.run(id)
            assertEquals("REVIEW", service.list().single().status)
            assertEquals(baseline, service.list().single().base)
            assertFalse(service.list().single().enabled)
            f.live = true
            assertEquals("baseline", f.text("shared.txt"))
            val reopened = f.service()
            reopened.run(id)
            assertEquals("COMPLETE", reopened.list().single().status)
            assertEquals("authorized edit", f.text("shared.txt"))
        }

    @Test fun providerScanDepthLimitLeavesBothTreesUntouched() =
        withFixture { f ->
            var path = ""
            repeat(33) { index ->
                path = if (path.isEmpty()) "d$index" else "$path/d$index"
                f.saf.create(path, true)
            }
            f.put("$path/leaf.txt", "deep source")
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    DirectorySyncScan { f.saf }.scan(SyncRoot(f.scope, "")) { false }
                }
            assertEquals("SYNC_TREE_LIMIT", failure.message)
            val service = f.service()
            val id = service.create(SyncRoot("app", "local"), SyncRoot(f.scope, "")).id
            service.run(id)
            assertEquals("REVIEW", service.list().single().status)
            assertEquals(0, service.list().single().copied)
            assertTrue(f.local.listFiles()!!.isEmpty())
            assertEquals("deep source", f.text("$path/leaf.txt"))
        }

    @Test fun interruptedPrivateTransferBlocksSyncUntilExplicitRecovery() =
        withFixture { f ->
            File(f.local, "source.txt").writeText("source retained")
            f.put("target.txt", "original retained")
            val record =
                ManualTransferRecord(
                    UUID.randomUUID().toString(),
                    "app",
                    "local/source.txt",
                    f.scope,
                    "target.txt",
                    false,
                )
            f.put(record.temporary, "partial write")
            f.journal().save(record)
            val service = f.service()
            val id = service.create(SyncRoot("app", "local"), SyncRoot(f.scope, "")).id
            service.run(id)
            assertEquals("REVIEW", service.list().single().status)
            assertEquals("original retained", f.text("target.txt"))
            assertEquals("partial write", f.text(record.temporary))
            assertEquals(listOf(record.id), f.operations().pendingTransfers().map { it.id })
            assertFalse(f.operations().recoverTransfer(record.id))
            assertEquals(null, f.saf.stat(record.temporary))
            assertEquals("original retained", f.text("target.txt"))
            assertEquals("source retained", File(f.local, "source.txt").readText())
            assertTrue(f.operations().pendingTransfers().isEmpty())
            val reopened = f.service()
            reopened.run(id)
            assertEquals("COMPLETE", reopened.list().single().status)
            assertEquals("source retained", f.text("source.txt"))
            assertEquals("original retained", File(f.local, "target.txt").readText())
        }

    private fun assertConflict(
        service: DirectorySyncService,
        path: String,
    ) {
        val rule = service.list().single()
        assertEquals("CONFLICT", rule.status)
        assertEquals(listOf(path), rule.conflicts)
        assertEquals(0, rule.copied)
        assertFalse(rule.enabled)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        Fixture().use(block)
    }

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context =
            instrumentation.targetContext.also {
                check(it.packageName == "com.helix.agent.developer.asrvalidation")
                check(it.applicationContext.javaClass == android.app.Application::class.java)
            }
        private val authority = "${instrumentation.context.packageName}.manualfiles"
        private val name = "directory-${UUID.randomUUID()}"
        private val documentId = "root/$name"
        private val resolver = context.contentResolver
        private val tree = DocumentsContract.buildTreeDocumentUri(authority, documentId)
        private val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        private val privateRoot = File(context.cacheDir, name).apply { check(mkdirs()) }
        private val registry = SafGrantStore(File(privateRoot, "grants.json").toPath())
        private val state = File(privateRoot, "sync.json")
        val local = File(privateRoot, "local").apply { check(mkdirs()) }
        var live = true
        val scope = registry.grant(tree.toString(), "Private provider fixture").scopeId
        private val service =
            SafTreeScopeService(
                registry,
                SafTreeGrantCheck { SafTreeGrantFacts(live, authority, documentId, true, true) },
            )
        val saf = SafManualFileBackend(resolver, registry, service, scope)
        private val nio = NioManualFileBackend("app", ScopeRootResolver { privateRoot.toPath() }, false) {}

        init {
            val rootTree = DocumentsContract.buildTreeDocumentUri(authority, "root")
            val root = DocumentsContract.buildDocumentUriUsingTree(rootTree, "root")
            requireNotNull(
                DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name),
            )
        }

        fun put(
            path: String,
            text: String,
        ) {
            if (saf.stat(path) == null) saf.create(path, false)
            saf.write(path).use { it.write(text.toByteArray()) }
        }

        fun text(path: String): String = saf.read(path).bufferedReader().use { it.readText() }

        fun journal() = ManualTransferJournal(File(privateRoot, "journal").toPath())

        fun operations() =
            ManualFileOperations(
                { requested ->
                    when (requested) {
                        scope -> saf
                        "app" -> nio
                        else -> error("Unexpected fixture scope")
                    }
                },
                { requested -> requested == "app" || (requested == scope && live) },
                journal(),
            )

        fun service() =
            DirectorySyncService(
                operations(),
                { state.takeIf { it.exists() }?.readText() },
                { state.writeText(it) },
                { _, _ -> },
            )

        fun store() =
            WorkspaceArtifactStore(
                ScopeRootResolver { error("SAF search must not use NIO") },
                documentBackend = { requested ->
                    check(requested == scope)
                    saf
                },
            )

        override fun close() {
            live = true
            try {
                // Only this UUID subtree is owned; never clear the shared provider root.
                val operations = operations()
                saf.children("").forEach { operations.delete(scope, it) }
                check(DocumentsContract.deleteDocument(resolver, rootDocument))
            } finally {
                check(privateRoot.deleteRecursively())
            }
        }
    }
}
