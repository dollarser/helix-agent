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
import com.helix.app.files.SharedStorageAccess
import com.helix.core.workspace.FileScopePath
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
        val approvedFolder = InstrumentationRegistry.getArguments().getString("approvedSafFolder")
        require(approvedFolder == null || approvedFolder.matches(Regex("Helix-validation-[0-9a-f]{8}")))
        val folder = approvedFolder ?: "HXA210-${UUID.randomUUID()}"
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
            val result = pick(chosen, approvedFolder)
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
            if (approvedFolder != null) verifyLocalDirectory(app, backend, folder)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.releasePersistableUriPermission(uri, flags)
            assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == uri })
            assertThrows(Exception::class.java) { container.safTree.resolve(source.scopeId, SafAccessMode.WRITE) }
            chosen.set(null)
            val renewed = pick(chosen, approvedFolder)
            assertEquals(uri, renewed.data)
            container.featureFiles.persistTreePermission(uri.toString(), renewed.flags)
            assertEquals(source.scopeId, container.safTree.grant(uri.toString(), folder).scopeId)
            container.safTree.resolve(source.scopeId, SafAccessMode.WRITE)
            assertEquals("system-provider-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
            backend.delete("proof.txt")
            if (android.os.Build.VERSION.SDK_INT >= 30) verifyRestrictedRoot(initial, chosen)
        } finally {
            try {
                closeOwnedPicker()
            } finally {
                scope?.let(container.safTree::revoke)
                granted?.let { release(app, it) }
                if (approvedFolder == null) shell("rm -rf $path")
            }
        }
    }

    @Test
    @Suppress("LongMethod") // One explicitly approved grant crosses read-only, write, revocation and process restart.
    fun realGrantModesAndRevocationAcrossProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val folder = args.getString("helixSafBoundaryFolder")
        org.junit.Assume.assumeTrue("Requires approval for the exact synthetic SAF directory", folder != null)
        require(requireNotNull(folder).matches(Regex("Helix-validation-[0-9a-f]{8}")))
        val phase = args.getString("helixSafBoundaryPhase")
        require(phase in setOf("seed", "recover"))
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val marker = java.io.File(app.filesDir, "$folder-saf-boundary.json")
        val documentId = "primary:Documents/$folder"
        val document = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        val initial = AtomicReference(document)
        val chosen = AtomicReference<Intent?>()
        compose.setContent {
            val picker = rememberLauncherForActivityResult(WorkspaceTreePicker()) { chosen.set(it) }
            Button(
                onClick = { picker.launch(initial.get()) },
                modifier = Modifier.testTag("system-tree-open"),
            ) { Text("Open") }
        }
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            if (phase == "seed") {
                check(!marker.exists()) { "Inspect the previous owned SAF fixture before repeating seed" }
                val before = grantSignature(app)
                val scopes = c.safTree.knownScopeIds().toSet()
                val result = pick(chosen, folder)
                val uri = requireNotNull(result.data)
                assertTrue(
                    DocumentsContract.getTreeDocumentId(uri) in setOf("primary:Documents/$folder", "home:$folder"),
                )
                check(app.contentResolver.persistedUriPermissions.none { it.uri == uri })
                c.featureFiles.persistTreePermission(
                    uri.toString(),
                    read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                val source = c.safTree.grant(uri.toString(), folder)
                val backend =
                    SafManualFileBackend(
                        app.contentResolver,
                        c.featureFiles.grantStore,
                        c.safTree,
                        source.scopeId,
                    )
                // Retain a recoverable marker before assertions or permission changes.
                marker.writeText(
                    org.json
                        .JSONObject()
                        .put("scope", source.scopeId)
                        .put("before", before)
                        .put("scopes", org.json.JSONArray(scopes.toList()))
                        .toString(),
                )
                assertEquals("original-saf-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
                assertThrows(Exception::class.java) { backend.write("proof.txt") }
                assertThrows(Exception::class.java) { backend.create("denied.txt", false) }
                assertThrows(IllegalArgumentException::class.java) { backend.read("../outside.txt") }
                assertEquals("original-saf-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
                c.featureFiles.persistTreePermission(uri.toString(), result.flags)
                backend.write("proof.txt").use { it.write("writable-saf-proof".toByteArray()) }
                app.contentResolver.releasePersistableUriPermission(uri, write)
                assertEquals("writable-saf-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
                assertThrows(Exception::class.java) { backend.write("proof.txt") }
                app.contentResolver.releasePersistableUriPermission(uri, read)
                assertTrue(app.contentResolver.persistedUriPermissions.none { it.uri == uri })
                assertEquals(before, grantSignature(app))
            } else {
                val saved = org.json.JSONObject(marker.readText())
                val scope = saved.getString("scope")
                val grant = requireNotNull(c.featureFiles.grantStore.find(scope))
                val uri = Uri.parse(grant.treeUri)
                assertTrue(
                    DocumentsContract.getTreeDocumentId(uri) in setOf("primary:Documents/$folder", "home:$folder"),
                )
                assertTrue(app.contentResolver.persistedUriPermissions.none { it.uri == uri })
                val backend = SafManualFileBackend(app.contentResolver, c.featureFiles.grantStore, c.safTree, scope)
                // This is a fresh process: no picker Activity's temporary grant may carry the read.
                assertThrows(Exception::class.java) { backend.read("proof.txt") }
                assertThrows(Exception::class.java) { backend.write("proof.txt") }
                val result = pick(chosen, folder)
                assertEquals(uri, result.data)
                c.featureFiles.persistTreePermission(uri.toString(), result.flags)
                assertEquals(scope, c.safTree.grant(uri.toString(), folder).scopeId)
                assertEquals("writable-saf-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
                backend.write("proof.txt").use { it.write("renewed-saf-proof".toByteArray()) }
                assertEquals("renewed-saf-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
                c.safTree.revoke(scope)
                release(app, uri)
                closeOwnedPicker()
                assertEquals(saved.getString("before"), grantSignature(app))
                val expected =
                    saved.getJSONArray("scopes").let { list ->
                        (0 until list.length()).map(list::getString).toSet()
                    }
                assertEquals(expected, c.safTree.knownScopeIds().toSet())
                java.io.File(app.filesDir, "$folder-saf-boundary-result.json").writeText(
                    org.json
                        .JSONObject()
                        .put("readOnlyRefusedWrite", true)
                        .put("persistedWriteDowngrade", true)
                        .put("revokedReadAndWriteRefusedAfterRestart", true)
                        .put("reauthorization", true)
                        .put("unrelatedGrantsPreserved", true)
                        .toString(),
                )
                marker.delete()
            }
        } finally {
            closeOwnedPicker()
        }
    }

    private fun grantSignature(app: HelixApplication): String {
        val grants =
            app.contentResolver.persistedUriPermissions
                .map {
                    "${it.uri}|${it.isReadPermission}|${it.isWritePermission}"
                }.sorted()
                .joinToString("\n")
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(grants.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun closeOwnedPicker() {
        val automation = instrumentation.uiAutomation
        repeat(5) {
            val owner = automation.rootInActiveWindow?.packageName?.toString()
            if (owner != null && !owner.endsWith(".documentsui")) return
            if (owner != null) {
                assertTrue(
                    automation.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                    ),
                )
            }
            Thread.sleep(200)
        }
        val owner = automation.rootInActiveWindow?.packageName?.toString()
        assertTrue(
            "Owned system picker must be closed before its host Activity is destroyed",
            owner != null && !owner.endsWith(".documentsui"),
        )
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

    private fun pick(
        chosen: AtomicReference<Intent?>,
        approvedFolder: String? = null,
    ): Intent {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        compose.onNodeWithTag("system-tree-open").performClick()
        if (approvedFolder != null) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (android.os.SystemClock.elapsedRealtime() < deadline &&
                automation.rootInActiveWindow?.let { root ->
                    find(root) { it.text?.toString() == approvedFolder }
                } == null
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "System picker must show the specifically approved directory before granting",
                automation.rootInActiveWindow?.let { root ->
                    find(root) { it.text?.toString() == approvedFolder }
                } != null,
            )
        }
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
                    ) {
                        it.viewIdResourceName == "android:id/button1" &&
                            it.text?.toString() in setOf("Allow", "ALLOW", "允许")
                    }
                }
            if (button != null) {
                if (approvedFolder != null) {
                    verifyApprovedConfirmation(approvedFolder)
                }
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            Thread.sleep(100)
        }
        compose.waitUntil(15_000) { chosen.get() != null }
        return requireNotNull(chosen.get())
    }

    private fun verifyApprovedConfirmation(folder: String) {
        assertTrue(
            "Permission confirmation must name only the approved directory",
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                find(root) {
                    it.viewIdResourceName == "android:id/message" && it.text?.toString()?.contains(folder) == true
                }
            } != null,
        )
    }

    private fun verifyLocalDirectory(
        app: HelixApplication,
        backend: SafManualFileBackend,
        folder: String,
    ) {
        assertTrue("Explicitly approved all-files permission must be present", SharedStorageAccess(app).isGranted())
        val local =
            backend.verifiedLocalDirectory("", SharedStorageAccess(app)) { authority ->
                val provider = app.packageManager.resolveContentProvider(authority, 0)
                provider != null &&
                    provider.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            }
        if (local != null) {
            val reference = FileScopePath.fromModelReference(local)
            val directory =
                SharedStorageAccess(app)
                    .resolveRoot(reference.scopeId)
                    .resolve(reference.relativePath)
                    .toFile()
                    .canonicalFile
            assertEquals(java.io.File("/sdcard/Documents/$folder").canonicalFile, directory)
        }
        // The owner separately approved this exact native directory; it is not inferred from the URI.
        val directory =
            SharedStorageAccess(app)
                .root()
                .resolve("Documents/$folder")
                .toFile()
                .canonicalFile
        val proof = java.io.File(directory, "proof.txt")
        assertEquals("system-provider-proof", proof.readText())
        proof.writeText("native-in-place-proof")
        assertEquals("native-in-place-proof", backend.read("proof.txt").bufferedReader().use { it.readText() })
        backend.write("proof.txt").use { it.write("system-provider-proof".toByteArray()) }
        java.io.File(app.filesDir, "physical-saf-proof.json").writeText(
            org.json
                .JSONObject()
                .put("folder", folder)
                .put("allFilesGranted", true)
                .put("safRoundTrip", true)
                .put("nativeInPlace", true)
                .put("safLocalAlternative", local != null)
                .toString(),
        )
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
