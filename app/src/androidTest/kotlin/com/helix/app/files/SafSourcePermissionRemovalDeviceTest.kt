package com.helix.app.files

import android.app.Application
import android.content.ContextWrapper
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
import com.helix.app.AppFileServices
import com.helix.app.ui.WorkspaceTreePicker
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.ContentResolverSafPermissionRelease
import com.helix.feature.files.SafGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real system picker plus the production app composition; no app container or source-file writes. */
class SafSourcePermissionRemovalDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Suppress("LongMethod") // One exact-grant lifecycle verifies modes, isolation and registry reopening.
    fun removingSourceReleasesExactPersistedModesAndPreservesOtherSources() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.javaClass == Application::class.java && app.packageName.endsWith(".asrvalidation"))
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("helixSafRemovalFolder"))
        require(folder.matches(Regex("Helix-reliability-[0-9a-f]{8}")))
        val resolver = app.contentResolver
        val before = signature(app)
        val root = File(app.noBackupFilesDir, "saf-removal-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context =
            object : ContextWrapper(app) {
                override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdirs()) }

                override fun getNoBackupFilesDir(): File = root

                override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdirs()) }
            }
        val files = AppFileServices(context, ScopeRootResolver { root.toPath() }, "app", { _, _ -> "" })
        val initial = AtomicReference<Uri>()
        val chosen = AtomicReference<Intent?>()
        compose.setContent {
            val picker = rememberLauncherForActivityResult(WorkspaceTreePicker()) { chosen.set(it) }
            Button(onClick = { picker.launch(initial.get()) }, modifier = Modifier.testTag("removal-tree-open")) {
                Text("Open")
            }
        }
        val owned = mutableSetOf<Uri>()
        val release = ContentResolverSafPermissionRelease(resolver)
        try {
            val other = pick(app, "$folder-keep", initial, chosen)
            owned += other
            resolver.takePersistableUriPermission(other, READ or WRITE)
            val kept = files.safTree.grant(other.toString(), "Kept source")
            val retained = signature(app)
            for (flags in listOf(READ, WRITE, READ or WRITE)) {
                val uri = pick(app, folder, initial, chosen)
                owned += uri
                resolver.takePersistableUriPermission(uri, flags)
                val source = files.safTree.grant(uri.toString(), folder)
                assertEquals(flags, permissionFlags(app, uri))
                assertTrue(files.safTree.revoke(source.scopeId))
                assertEquals(retained, signature(app))
                assertFalse(files.safTree.revoke(source.scopeId))
                assertThrows(ScopeNotAvailable::class.java) { files.safTree.resolve(source.scopeId) }
                val reopened = SafGrantStore(File(context.filesDir, "workspaces/saf-grants.json").toPath())
                assertEquals(listOf(kept.scopeId), reopened.list().map { it.scopeId })
            }
            assertEquals(kept.scopeId, files.safTree.resolve(kept.scopeId).scopeId)
            assertTrue(files.safTree.revoke(kept.scopeId))
            assertEquals(before, signature(app))
        } finally {
            try {
                SystemSafPickerControls().closeOwnedPicker()
            } finally {
                owned.forEach { release.release(it.toString()) }
                assertEquals(before, signature(app))
            }
        }
    }

    private fun pick(
        app: Application,
        folder: String,
        initial: AtomicReference<Uri>,
        chosen: AtomicReference<Intent?>,
    ): Uri {
        val documentId = "primary:Documents/$folder"
        val uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId)
        check(app.contentResolver.persistedUriPermissions.none { it.uri == uri })
        initial.set(DocumentsContract.buildDocumentUri(AUTHORITY, documentId))
        chosen.set(null)
        compose.onNodeWithTag("removal-tree-open").performClick()
        SystemSafPickerControls().selectExactFolder(folder)
        compose.waitUntil(15_000) { chosen.get() != null }
        val result = requireNotNull(chosen.get())
        assertEquals(uri, result.data)
        assertEquals(READ or WRITE, result.flags and (READ or WRITE))
        return uri
    }

    private fun permissionFlags(
        app: Application,
        uri: Uri,
    ): Int {
        val permission = app.contentResolver.persistedUriPermissions.single { it.uri == uri }
        return (if (permission.isReadPermission) READ else 0) or (if (permission.isWritePermission) WRITE else 0)
    }

    private fun signature(app: Application): List<String> =
        app.contentResolver.persistedUriPermissions
            .map { "${it.uri}|${it.isReadPermission}|${it.isWritePermission}" }
            .sorted()

    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
