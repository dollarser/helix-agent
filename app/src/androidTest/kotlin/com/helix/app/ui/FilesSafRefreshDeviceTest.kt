package com.helix.app.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppFileServices
import com.helix.app.files.SystemSafPickerControls
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.ContentResolverSafPermissionRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real picker, grant action, effects and dialog; isolated package and one owned empty directory. */
class FilesSafRefreshDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Suppress("LongMethod") // One unchanged open dialog exercises add, cancel, revoke and re-add.
    fun openDialogRefreshesAfterAddingAndReaddingWithoutRetry() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.javaClass == Application::class.java && app.packageName.endsWith(".asrvalidation"))
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("helixSafRefreshFolder"))
        require(folder.matches(Regex("Helix-reliability-[0-9a-f]{8}")))
        val documentId = "primary:Documents/$folder"
        val uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId)
        val initial = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        val before = signature(app)
        check(app.contentResolver.persistedUriPermissions.none { it.uri == uri })
        val files = isolatedFiles(app)
        val state = FilesScreenState(files.fileManager).apply { safPanelOpen = true }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val actions =
                FilesScreenActions(
                    state,
                    files.fileManager,
                    files.safTree,
                    files.featureFiles,
                    scope,
                    app,
                    app.resources,
                )
            val picker =
                rememberLauncherForActivityResult(WorkspaceTreePicker()) { result ->
                    result?.data?.let { actions.grantTree(it, result.flags) }
                }
            MaterialTheme {
                FilesScopeEffects(state, actions)
                FilesSafDialog(state, actions) { picker.launch(initial) }
            }
        }
        try {
            compose.onNodeWithTag("files-saf-empty").assertIsDisplayed()
            repeat(2) {
                compose.onNodeWithTag("files-saf-add").performClick()
                SystemSafPickerControls().selectExactFolder(folder)
                compose.waitUntil(15_000) { state.safSources.size == 1 }
                val source = state.safSources.single()
                compose.runOnIdle {
                    assertTrue(state.safPanelOpen)
                    assertFalse(state.safLoadFailed)
                    assertEquals(source.scopeId, files.safTree.resolve(source.scopeId).scopeId)
                    assertTrue(state.sources.any { it.scopeId == source.scopeId })
                }
                compose.onNodeWithTag("files-saf-source-${source.scopeId}").assertIsDisplayed()
                compose.onNodeWithTag("files-saf-retry").assertDoesNotExist()
                compose.onNodeWithTag("files-saf-empty").assertDoesNotExist()
                assertEquals(READ or WRITE, permissionFlags(app, uri))
                compose.onNodeWithTag("files-saf-remove-${source.scopeId}").performClick()
                compose.waitUntil(15_000) { state.safSources.isEmpty() }
                compose.onNodeWithTag("files-saf-empty").assertIsDisplayed()
                assertEquals(before, signature(app))

                // Cancelling the picker leaves the open dialog empty; it cannot restore a grant.
                compose.onNodeWithTag("files-saf-add").performClick()
                compose.waitUntil(15_000) {
                    InstrumentationRegistry
                        .getInstrumentation()
                        .uiAutomation.rootInActiveWindow
                        ?.packageName
                        ?.toString()
                        ?.endsWith(".documentsui") == true
                }
                SystemSafPickerControls().closeOwnedPicker()
                compose.waitForIdle()
                compose.onNodeWithTag("files-saf-empty").assertIsDisplayed()
                assertEquals(before, signature(app))
            }
            compose.onNodeWithTag("files-saf-close").performClick()
            compose.onNodeWithTag("files-saf-dialog").assertDoesNotExist()
            compose.runOnIdle { state.safPanelOpen = true }
            compose.onNodeWithTag("files-saf-empty").assertIsDisplayed()
            assertEquals(before, signature(app))
        } finally {
            try {
                SystemSafPickerControls().closeOwnedPicker()
            } finally {
                ContentResolverSafPermissionRelease(app.contentResolver).release(uri.toString())
                assertEquals(before, signature(app))
            }
        }
    }

    private fun isolatedFiles(app: Application): AppFileServices {
        val root = File(app.noBackupFilesDir, "saf-refresh-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context =
            object : ContextWrapper(app) {
                override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdirs()) }

                override fun getNoBackupFilesDir(): File = root

                override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdirs()) }
            }
        return AppFileServices(context, ScopeRootResolver { root.toPath() }, "app", { _, _ -> "" })
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
