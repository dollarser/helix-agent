package com.helix.app.files

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.ui.WorkspaceTreePicker
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.ContentResolverSafTreeCheck
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeScopeService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual death after a confirmed system-provider rename; never targets the daily app. */
class SafSystemProcessRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun publishedSyncSurvivesProcessDeathWithoutAutomaticReplay() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.javaClass == Application::class.java)
        check(app.packageName == "com.helix.agent.developer.asrvalidation")
        val arguments = InstrumentationRegistry.getArguments()
        val folder = requireNotNull(arguments.getString("helixSafReliabilityFolder"))
        require(folder == "Helix-reliability-0811abcd")
        val phase = requireNotNull(arguments.getString("recoveryPhase"))
        require(phase in setOf("setup", "verify", "release-grant"))
        val root = File(app.noBackupFilesDir, "saf-process-$folder")
        if (phase == "setup") {
            check(!root.exists()) { "Never overwrite a prior process-recovery fixture" }
            check(root.mkdir())
            setup(app, folder, root)
        } else {
            val identity = JSONObject(File(root, "identity.json").readText())
            assertEquals(folder, identity.getString("folder"))
            val grant = Uri.parse(identity.getString("tree"))
            assertEquals(AUTHORITY, grant.authority)
            assertEquals("primary:Documents/$folder", DocumentsContract.getTreeDocumentId(grant))
            try {
                if (phase == "verify") verify(app, root, identity)
            } finally {
                releaseGrant(app, grant)
                assertEquals(jsonStrings(identity.getJSONArray("grantsBefore")), grants(app))
                persist(root, "grant-cleanup.json", JSONObject().put("grantsRestored", true).toString())
            }
        }
    }

    private fun setup(
        app: Application,
        folder: String,
        root: File,
    ) {
        val originalGrants = grants(app)
        val tree = pickTree(app, folder)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val identity =
            JSONObject()
                .put("folder", folder)
                .put("tree", tree.toString())
                .put("grantsBefore", JSONArray(originalGrants))
                .put("owned", "owned-${UUID.randomUUID()}")
                .put("setupPid", Process.myPid())
        // Save exact cleanup authority before taking the persisted grant.
        persist(root, "identity.json", identity.toString())
        try {
            app.contentResolver.takePersistableUriPermission(tree, flags)
            val registry = SafGrantStore(File(root, "grants.json").toPath())
            val scope = registry.grant(tree.toString(), folder).scopeId
            identity.put("scope", scope)
            persist(root, "identity.json", identity.toString())
            val fixture = Fixture(app, root, identity)
            check(fixture.saf.children("").isEmpty()) { "The exact approved directory must be empty" }
            fixture.saf.create(fixture.owned, true)
            check(fixture.local.mkdir())
            fixture.source.writeText("original bytes")
            val sync = fixture.service(fixture.operations())
            val rule = sync.create(SyncRoot("app", "local"), SyncRoot(scope, fixture.owned))
            sync.run(rule.id)
            assertEquals("COMPLETE", sync.list().single().status)
            assertEquals("original bytes", fixture.text(fixture.target))
            identity.put("rule", rule.id)
            identity.put("baseline", DirectorySyncCodec.encode(sync.list()))
            persist(root, "identity.json", identity.toString())
            sync.enable(rule.id, true)
            fixture.source.writeText("replacement bytes")
            fixture.service(fixture.operations(killAfterPublish = true)).run(rule.id)
            error("Expected actual process death immediately after provider publication")
        } finally {
            // Process.killProcess deliberately bypasses this; verify/release-grant closes it in a new process.
            releaseGrant(app, tree)
            assertEquals(originalGrants, grants(app))
        }
    }

    @Suppress("LongMethod") // One crash boundary is verified before explicit recovery and owned cleanup.
    private fun verify(app: Application, root: File, identity: JSONObject) {
        val cut = JSONObject(File(root, "cut.json").readText())
        val oldPid = identity.getInt("setupPid")
        assertNotEquals(oldPid, Process.myPid())
        assertEquals(oldPid, cut.getInt("pid"))
        assertEquals("after-confirmed-provider-rename", cut.getString("cut"))
        val fixture = Fixture(app, root, identity)
        val record = fixture.journal.list().single()
        assertEquals(ManualTransferPhase.PUBLISHING, record.phase)
        assertEquals(fixture.target, record.target)
        assertEquals(fixture.scope, record.targetScope)
        assertEquals("app", record.sourceScope)
        assertEquals("local/shared.txt", record.source)
        assertFalse(record.move)
        assertEquals("replacement bytes", fixture.source.readText())
        assertEquals("replacement bytes", fixture.text(record.target))
        assertEquals("original bytes", fixture.text(record.backup))
        assertEquals(null, fixture.saf.stat(record.temporary))
        val rawRule = DirectorySyncCodec.decode(File(root, "sync.json").readText()).single()
        assertEquals("RUNNING", rawRule.status)
        assertTrue(rawRule.enabled)
        val baseline = DirectorySyncCodec.decode(identity.getString("baseline")).single().base
        assertEquals(baseline, rawRule.base)
        val mutations = mutableListOf<String>()
        val operations = fixture.operations(mutations = mutations)
        val scheduling = mutableListOf<Boolean>()
        val sync = fixture.service(operations, scheduling)
        assertEquals(listOf(false), scheduling)
        val id = identity.getString("rule")
        assertEquals(id, sync.list().single().id)
        assertEquals("REVIEW", sync.list().single().status)
        assertFalse(sync.list().single().enabled)
        assertEquals(baseline, sync.list().single().base)
        sync.run(id, automatic = true)
        assertEquals("REVIEW", sync.list().single().status)
        assertTrue(mutations.isEmpty())
        assertEquals(listOf(record.id), operations.pendingTransfers().map { it.id })
        assertEquals("replacement bytes", fixture.source.readText())
        assertEquals("replacement bytes", fixture.text(record.target))
        assertEquals("original bytes", fixture.text(record.backup))
        // Explicit recovery observes the already-published bytes; it does not rerun a copy or delete the source.
        assertTrue(operations.recoverTransfer(record.id))
        assertEquals(listOf("delete:${record.backup}"), mutations)
        assertTrue(operations.pendingTransfers().isEmpty())
        assertEquals("replacement bytes", fixture.source.readText())
        assertEquals("replacement bytes", fixture.text(record.target))
        mutations.clear()
        sync.run(id)
        assertEquals("COMPLETE", sync.list().single().status)
        assertEquals(0, sync.list().single().copied)
        assertTrue(mutations.isEmpty())
        assertEquals(
            "COMPLETE",
            fixture
                .service(fixture.operations())
                .list()
                .single()
                .status,
        )
        persist(
            root,
            "verified.json",
            JSONObject()
                .put("status", "passed")
                .put("setupPid", oldPid)
                .put("verifyPid", Process.myPid())
                .put("scope", fixture.scope)
                .put("target", record.target)
                .put("transfer", record.id)
                .put("automaticReplay", false)
                .put("explicitRecovery", true)
                .toString(),
        )
        fixture.operations().delete(fixture.scope, fixture.owned)
        assertTrue(fixture.saf.children("").isEmpty())
        check(fixture.local.deleteRecursively())
        persist(root, "content-cleanup.json", JSONObject().put("ownedProviderContentsRemoved", true).toString())
    }

    private fun pickTree(
        app: Application,
        folder: String,
    ): Uri {
        val documentId = "primary:Documents/$folder"
        val initial = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        check(
            app.contentResolver.persistedUriPermissions.none {
                it.uri.authority == AUTHORITY && DocumentsContract.isTreeUri(it.uri) &&
                    DocumentsContract.getTreeDocumentId(it.uri) == documentId
            },
        )
        val chosen = AtomicReference<Intent?>()
        compose.setContent {
            val picker = rememberLauncherForActivityResult(WorkspaceTreePicker()) { chosen.set(it) }
            Button(onClick = { picker.launch(initial) }, modifier = Modifier.testTag("saf-process-open")) {
                Text("Open")
            }
        }
        compose.onNodeWithTag("saf-process-open").performClick()
        val controls = SystemSafPickerControls()
        try {
            controls.selectExactFolder(folder)
            compose.waitUntil(15_000) { chosen.get() != null }
            val result = requireNotNull(chosen.get())
            val tree = requireNotNull(result.data)
            assertEquals(AUTHORITY, tree.authority)
            assertEquals(documentId, DocumentsContract.getTreeDocumentId(tree))
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            assertEquals(flags, result.flags and flags)
            return tree
        } finally {
            controls.closeOwnedPicker()
        }
    }

    private class Fixture(
        app: Application,
        val root: File,
        identity: JSONObject,
    ) {
        val scope = identity.getString("scope")
        val owned = identity.getString("owned").also { require(it.matches(Regex("owned-[0-9a-f-]{36}"))) }
        val local = File(root, "local")
        val source = File(local, "shared.txt")
        val target = "$owned/shared.txt"
        val journal = ManualTransferJournal(File(root, "journal").toPath())
        private val registry = SafGrantStore(File(root, "grants.json").toPath())
        private val scopes = SafTreeScopeService(registry, ContentResolverSafTreeCheck(app.contentResolver))
        val saf = SafManualFileBackend(app.contentResolver, registry, scopes, scope)
        private val nio = NioManualFileBackend("app", ScopeRootResolver { root.toPath() }, false) {}

        fun text(path: String): String = saf.read(path).bufferedReader().use { it.readText() }

        fun operations(
            killAfterPublish: Boolean = false,
            mutations: MutableList<String> = mutableListOf(),
        ): ManualFileOperations {
            val tracked =
                object : ManualFileBackend by saf {
                    override fun rename(
                        path: String,
                        destination: String,
                    ) {
                        mutations.add("rename:$path:$destination")
                        saf.rename(path, destination)
                        if (killAfterPublish && destination == target &&
                            path.substringAfterLast('/').startsWith(".helix-transfer-")
                        ) {
                            persist(
                                root,
                                "cut.json",
                                JSONObject()
                                    .put("pid", Process.myPid())
                                    .put("cut", "after-confirmed-provider-rename")
                                    .toString(),
                            )
                            Process.killProcess(Process.myPid())
                            error("Process kill unexpectedly returned")
                        }
                    }

                    override fun create(
                        path: String,
                        directory: Boolean,
                    ) {
                        mutations.add("create:$path")
                        saf.create(path, directory)
                    }

                    override fun write(path: String): java.io.OutputStream {
                        mutations.add("write:$path")
                        return saf.write(path)
                    }

                    override fun delete(path: String) {
                        mutations.add("delete:$path")
                        saf.delete(path)
                    }
                }
            return ManualFileOperations(
                { requested ->
                    when (requested) {
                        scope -> tracked
                        "app" -> nio
                        else -> error("Unexpected scope")
                    }
                },
                { requested ->
                    requested == "app" ||
                        (requested == scope && scopes.resolve(scope, SafAccessMode.WRITE).writable)
                },
                journal,
            )
        }

        fun service(
            operations: ManualFileOperations,
            scheduling: MutableList<Boolean> = mutableListOf(),
        ) = DirectorySyncService(
            operations,
            { File(root, "sync.json").takeIf { it.exists() }?.readText() },
            { persist(root, "sync.json", it) },
            // Record scheduling decisions without installing actual background work in the plain test host.
            { _, enabled -> scheduling.add(enabled) },
        )
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"

        fun persist(
            root: File,
            name: String,
            value: String,
        ) = AtomicFileWriter.writeAtomic(File(root, name).toPath(), value.toByteArray())

        fun grants(app: Application): List<String> =
            app.contentResolver.persistedUriPermissions
                .map {
                    "${it.uri}|${it.isReadPermission}|${it.isWritePermission}"
                }.sorted()

        fun jsonStrings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

        fun releaseGrant(
            app: Application,
            tree: Uri,
        ) {
            app.contentResolver.persistedUriPermissions.singleOrNull { it.uri == tree }?.let {
                val flags =
                    (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                app.contentResolver.releasePersistableUriPermission(tree, flags)
            }
        }
    }
}
