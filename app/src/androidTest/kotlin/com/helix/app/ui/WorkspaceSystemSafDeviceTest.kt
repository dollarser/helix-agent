package com.helix.app.ui

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
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
import com.helix.app.HelixApplication
import com.helix.app.files.SafManualFileBackend
import com.helix.feature.files.SafAccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real DocumentsUI and ExternalStorageProvider; no substituted activity result or grant checker. */
class WorkspaceSystemSafDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Suppress("LongMethod") // Grant, mutation, revoke and regrant are one resource lifecycle.
    @Test
    fun systemPickerPersistsOnlyGrantedAccessAndReauthorizationRestoresWrite() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val folder = "HXA210-${UUID.randomUUID()}"
        val path = "/sdcard/Documents/$folder"
        shell("mkdir -p $path")
        val initial =
            AtomicReference(
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents/$folder",
                ),
            )
        val chosen = AtomicReference<Intent?>()
        compose.setContent {
            val picker = rememberLauncherForActivityResult(WorkspaceTreePicker()) { chosen.set(it) }
            Button(
                onClick = { picker.launch(initial.get()) },
                modifier = Modifier.testTag("system-tree-open"),
            ) { Text("Open") }
        }
        var scope: String? = null
        var granted: Uri? = null
        try {
            val result = pick(chosen)
            val uri = requireNotNull(result.data)
            granted = uri
            assertTrue(DocumentsContract.getTreeDocumentId(uri) in setOf("primary:Documents/$folder", "home:$folder"))
            container.featureFiles.persistTreePermission(uri.toString(), result.flags)
            val source = container.safTree.grant(uri.toString(), folder)
            scope = source.scopeId
            container.safTree.resolve(source.scopeId, SafAccessMode.WRITE)
            val backend =
                SafManualFileBackend(
                    app.contentResolver,
                    container.featureFiles.grantStore,
                    container.safTree,
                    source.scopeId,
                )
            backend.create("proof.txt", false)
            backend.write("proof.txt").use { it.write("system-provider-proof".toByteArray()) }
            assertEquals("system-provider-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.releasePersistableUriPermission(uri, flags)
            assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == uri })
            assertThrows(Exception::class.java) { container.safTree.resolve(source.scopeId, SafAccessMode.WRITE) }
            chosen.set(null)
            val renewed = pick(chosen)
            assertEquals(uri, renewed.data)
            container.featureFiles.persistTreePermission(uri.toString(), renewed.flags)
            assertEquals(source.scopeId, container.safTree.grant(uri.toString(), folder).scopeId)
            container.safTree.resolve(source.scopeId, SafAccessMode.WRITE)
            assertEquals("system-provider-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
            backend.delete("proof.txt")
            if (android.os.Build.VERSION.SDK_INT >= 30) verifyRestrictedRoot(initial, chosen)
        } finally {
            scope?.let(container.safTree::revoke)
            granted?.let { release(app, it) }
            shell("rm -rf $path")
        }
    }

    private fun release(
        app: HelixApplication,
        uri: Uri,
    ) {
        val permission = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags =
            (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        app.contentResolver.releasePersistableUriPermission(uri, flags)
    }

    private fun verifyRestrictedRoot(
        initial: AtomicReference<Uri>,
        chosen: AtomicReference<Intent?>,
    ) {
        initial.set(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
        chosen.set(null)
        compose.onNodeWithTag("system-tree-open").performClick()
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        var refused = false
        while (!refused && android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val button = root?.let { find(it) { node -> node.viewIdResourceName == "android:id/button1" } }
            refused = button != null && !button.isEnabled
            Thread.sleep(100)
        }
        assertTrue("Android 11+ must refuse selecting the Downloads root", refused)
        assertEquals(null, chosen.get())
        assertTrue(
            instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            ),
        )
    }

    private fun pick(chosen: AtomicReference<Intent?>): Intent {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        compose.onNodeWithTag("system-tree-open").performClick()
        clickSystemNode {
            it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.viewIdResourceName == "android:id/button1"
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (chosen.get() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            val button =
                automation.rootInActiveWindow?.let { root ->
                    find(
                        root,
                    ) { it.viewIdResourceName == "android:id/button1" && it.text?.toString().equals("Allow", true) }
                }
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(100)
        }
        compose.waitUntil(15_000) { chosen.get() != null }
        return requireNotNull(chosen.get())
    }

    private fun clickSystemNode(predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = root?.let { find(it, predicate) }
            if (node != null && node.isEnabled) {
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            Thread.sleep(100)
        }
        error("Expected system picker action did not become available")
    }

    @Suppress("ReturnCount") // Recursive tree search exits immediately on the first matching system control.
    private fun find(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, predicate)?.let { return it } }
        }
        return null
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }
}
