package com.helix.app.eval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.files.FileManagerService
import com.helix.app.ui.fileShareIntent
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.feature.files.ContentResolverSafTreeDestination
import com.helix.feature.files.ContentResolverSafTreeLister
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real system-provider journey. Only the newly selected dedicated fixture grant is revoked. */
class RealSafFileDeviceTest {
    @Suppress("LongMethod") // Preserve the selected grant, real original, separate-UID share and revocation journey.
    @Test
    fun createReadOriginalShareEntireFileAndDetectRevocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("helixSafFixture"))
        require(folder.matches(Regex("Helix-closeout-[A-Za-z0-9-]+")))
        val resolver = app.contentResolver
        val permission =
            resolver.persistedUriPermissions.single {
                it.uri.authority == "com.android.externalstorage.documents" &&
                    DocumentsContract.getTreeDocumentId(it.uri) == "primary:Documents/$folder"
            }
        require(permission.isReadPermission && permission.isWritePermission)
        val source = container.safTree.liveSources().single { it.displayName.contains(folder) }
        val name = "original-${UUID.randomUUID()}.txt"
        val manager = container.fileManager
        try {
            assertTrue(manager.createFile(source.scopeId, "", name) is FileManagerService.FileOpResult.Ok)
            assertTrue(manager.createFile(source.scopeId, "", name) is FileManagerService.FileOpResult.Conflict)
            val root = DocumentsContract.getTreeDocumentId(permission.uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(permission.uri, root)
            val documentId =
                requireNotNull(
                    resolver.query(children, arrayOf("document_id", "_display_name"), null, null, null),
                ).use {
                    var id: String? = null
                    while (it.moveToNext()) if (it.getString(1) == name) id = it.getString(0)
                    requireNotNull(id)
                }
            val original = DocumentsContract.buildDocumentUriUsingTree(permission.uri, documentId)
            val bytes = ("Real SAF original\n" + "reader/share boundary\n".repeat(5000) + "END").toByteArray()
            requireNotNull(resolver.openOutputStream(original, "wt")).use { it.write(bytes) }
            assertEquals(String(bytes), manager.viewerContent(source.scopeId, name).text)
            val listed = ContentResolverSafTreeLister(resolver).listTree(permission.uri.toString())
            val imported = listed.single { it.rawSegments == listOf(name) }
            assertTrue(imported.documentUri.contains("/tree/"))
            requireNotNull(resolver.openInputStream(android.net.Uri.parse(imported.documentUri))).use {
                assertTrue(bytes.contentEquals(it.readBytes()))
            }
            val registry = SafGrantStore(app.cacheDir.resolve("real-saf-${UUID.randomUUID()}.json").toPath())
            registry.grant(permission.uri.toString(), folder)
            val destination = ContentResolverSafTreeDestination(resolver, registry)
            val exported =
                android.net.Uri.parse(
                    destination.destinationUri(source.scopeId, "", "export-$name", "text/plain", false),
                )
            assertTrue(exported.toString().contains("/tree/"))
            requireNotNull(resolver.openOutputStream(exported, "wt")).use { it.write(bytes) }
            requireNotNull(resolver.openInputStream(exported)).use { assertTrue(bytes.contentEquals(it.readBytes())) }
            val shared = manager.realFileFor(source.scopeId, name)
            assertTrue(bytes.contentEquals(shared.readBytes()))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", shared, name)
            val action = "${app.packageName}.SAF_SHARE_RECEIPT.${UUID.randomUUID()}"
            val completed = CountDownLatch(1)
            var receipt: Intent? = null
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        receipt = intent
                        completed.countDown()
                    }
                }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
            try {
                app.startActivity(
                    fileShareIntent(uri, "text/plain")
                        .setClassName(instrumentation.context.packageName, FileShareReceiptActivity::class.java.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("receiptAction", action)
                        .putExtra("receiptPackage", app.packageName),
                )
                assertTrue("External recipient did not return a receipt", completed.await(15, TimeUnit.SECONDS))
                val actual = requireNotNull(receipt)
                assertEquals(null, actual.getStringExtra("error"))
                assertNotEquals(android.os.Process.myUid(), actual.getIntExtra("uid", -1))
                assertEquals(bytes.size.toLong(), actual.getLongExtra("bytes", -1))
                val expectedHash =
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                assertEquals(expectedHash, actual.getStringExtra("sha256"))
            } finally {
                app.unregisterReceiver(receiver)
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            resolver.releasePersistableUriPermission(
                permission.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            assertThrows(
                ScopeNotAvailable::class.java,
            ) { container.safTree.resolve(source.scopeId, SafAccessMode.WRITE) }
            assertThrows(
                ScopeNotAvailable::class.java,
            ) { container.safTree.resolve(source.scopeId, SafAccessMode.READ) }
            assertThrows(ScopeNotAvailable::class.java) { manager.viewerContent(source.scopeId, name) }
            assertTrue(
                manager.createFile(source.scopeId, "", "after-revoke.txt") is FileManagerService.FileOpResult.Error,
            )
        } finally {
            if (resolver.persistedUriPermissions.any { it.uri == permission.uri }) {
                resolver.releasePersistableUriPermission(
                    permission.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            container.safTree.revoke(source.scopeId)
        }
    }
}
