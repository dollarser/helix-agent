package com.helix.app.files

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.helix.feature.files.ContentResolverSafTreeCheck
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeScopeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real DocumentsUI/ExternalStorageProvider with a plain Application and no daily app container. */
class SafSystemDirectoryReliabilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    // Nested finally blocks guarantee exact-grant release after picker or cleanup failure.
    @Suppress("LongMethod", "NestedBlockDepth")
    fun systemProviderSearchAndSyncConflictRecoverAfterServiceReopen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.javaClass == Application::class.java) { "Requires the plain Application isolation runner" }
        check(app.packageName.endsWith(".asrvalidation")) { "Never run in the daily application" }
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("helixSafReliabilityFolder"))
        require(folder.matches(Regex("Helix-reliability-[0-9a-f]{8}")))
        val resolver = app.contentResolver
        val before = grantSignature(app)
        val documentId = "primary:Documents/$folder"
        val initial = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        val chosen = AtomicReference<Intent?>()
        val fixtureName = "owned-${UUID.randomUUID()}"
        val privateRoot = File(app.noBackupFilesDir, fixtureName).apply { check(mkdirs()) }
        val receipt = File(privateRoot, "fixture.json")
        receipt.writeText(
            org.json
                .JSONObject()
                .put("folder", folder)
                .put("subtree", fixtureName)
                .put("status", "pending")
                .toString(),
        )
        compose.setContent {
            val picker = rememberLauncherForActivityResult(WorkspaceTreePicker()) { chosen.set(it) }
            Button(onClick = { picker.launch(initial) }, modifier = Modifier.testTag("reliability-tree-open")) {
                Text("Open")
            }
        }
        var ownedGrant: Uri? = null
        var succeeded = false
        try {
            // The runner owns an already-created, new empty folder. This test never creates it via shell.
            check(
                resolver.persistedUriPermissions.none {
                    it.uri.authority == AUTHORITY && DocumentsContract.isTreeUri(it.uri) &&
                        DocumentsContract.getTreeDocumentId(it.uri) == documentId
                },
            ) { "The exact fixture directory already has a persisted grant" }
            val result = pick(chosen, folder)
            val uri = requireNotNull(result.data)
            assertEquals(AUTHORITY, uri.authority)
            assertEquals(documentId, DocumentsContract.getTreeDocumentId(uri))
            check(resolver.persistedUriPermissions.none { it.uri == uri })
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            assertEquals(flags, result.flags and flags)
            ownedGrant = uri
            resolver.takePersistableUriPermission(uri, flags)
            val grants = SafGrantStore(File(privateRoot, "grants.json").toPath())
            val scopes = SafTreeScopeService(grants, ContentResolverSafTreeCheck(resolver))
            val scope = grants.grant(uri.toString(), folder).scopeId
            scopes.resolve(scope, SafAccessMode.WRITE)
            val backend = SafManualFileBackend(resolver, grants, scopes, scope)
            check(backend.children("").isEmpty()) { "The approved fixture folder must be new and empty" }
            backend.create(fixtureName, true)
            val contract =
                SafSystemDirectoryContract(backend, scope, fixtureName, privateRoot) {
                    scopes.resolve(scope, SafAccessMode.WRITE).writable
                }
            contract.verify()
            contract.removeOwnedSubtree()
            assertTrue(backend.children("").isEmpty())
            succeeded = true
        } finally {
            try {
                SystemSafPickerControls().closeOwnedPicker()
            } finally {
                try {
                    ownedGrant?.let { uri ->
                        resolver.persistedUriPermissions.singleOrNull { it.uri == uri }?.let { permission ->
                            val flags =
                                (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                            resolver.releasePersistableUriPermission(uri, flags)
                        }
                    }
                    assertEquals(before, grantSignature(app))
                    receipt.writeText(
                        org.json
                            .JSONObject()
                            .put("folder", folder)
                            .put("subtree", fixtureName)
                            .put("status", if (succeeded) "passed" else "failed-fixture-retained")
                            .put("grantsRestored", true)
                            .put("serviceReopenOnly", true)
                            .toString(),
                    )
                } finally {
                    // Failure keeps both the owned provider subtree and private state for inspection.
                    // The runner may rmdir its folder only after a passed instrumentation result.
                    if (succeeded) {
                        check(File(privateRoot, "local").deleteRecursively())
                    }
                }
            }
        }
    }

    private fun grantSignature(app: Application): List<String> =
        app.contentResolver.persistedUriPermissions
            .map {
                "${it.uri}|${it.isReadPermission}|${it.isWritePermission}"
            }.sorted()

    private fun pick(
        chosen: AtomicReference<Intent?>,
        folder: String,
    ): Intent {
        compose.onNodeWithTag("reliability-tree-open").performClick()
        SystemSafPickerControls().selectExactFolder(folder)
        compose.waitUntil(15_000) { chosen.get() != null }
        return requireNotNull(chosen.get())
    }

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
