package com.helix.app.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.files.FileManagerService
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** System chooser boundary plus an explicit synthetic-only receiver in the separate test APK UID. */
class RetainedSharingDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Suppress("LongMethod") // File button -> chooser -> granted receiver -> owned fixture cleanup.
    @Test
    fun fileButtonSharesReadableTypedContentWithoutWriteAccess() {
        val root = File(context.filesDir, "workspaces/share-fixture-${UUID.randomUUID()}")
        val roots = ScopeRootResolver { root.toPath() }
        val store = WorkspaceArtifactStore(roots)
        val scope = "ws-fixture"
        store.ensureLayout(scope)
        File(root, "work/report.txt").writeText("synthetic file body")
        val files = FileManagerService(store, roots, scope)
        val chooser = AtomicReference<Intent?>()
        val capture =
            object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    chooser.set(intent)
                }
            }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides capture) {
                    MaterialTheme { FileShareAction(files, FileScopePath(scope, "work/report.txt"), true) }
                }
            }
            compose.onNodeWithTag("artifact-share-file").performClick()
            compose.waitUntil(5000) { chooser.get() != null }
            val wrapper = requireNotNull(chooser.get())
            assertEquals(Intent.ACTION_CHOOSER, wrapper.action)
            val send = requireNotNull(IntentCompat.getParcelableExtra(wrapper, Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            val uri =
                requireNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, android.net.Uri::class.java))
            assertEquals("content", uri.scheme)
            assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
            assertTrue(wrapper.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val receipt = receive(send)
            assertEquals("synthetic file body", receipt.getStringExtra("body"))
            assertEquals("text/plain", receipt.getStringExtra("mime"))
            assertEquals("text/plain", receipt.getStringExtra("resolverMime"))
            assertEquals(PackageManager.PERMISSION_GRANTED, receipt.getIntExtra("readPermission", -2))
            assertEquals(PackageManager.PERMISSION_DENIED, receipt.getIntExtra("writePermission", -2))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun taskResultTextUsesChooserAndFixtureReceiver() {
        var chooser: Intent? = null
        val capture =
            object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    chooser = intent
                }
            }
        assertTrue(sharePlainText(capture, "synthetic task result"))
        assertEquals(Intent.ACTION_CHOOSER, chooser?.action)
        val send =
            requireNotNull(
                IntentCompat.getParcelableExtra(requireNotNull(chooser), Intent.EXTRA_INTENT, Intent::class.java),
            )
        assertEquals("text/plain", send.type)
        assertEquals("synthetic task result", receive(send).getStringExtra("body"))
    }

    @Test fun unknownMimeIsBinaryAndFileSchemeIsRejected() {
        val intent = fileShareIntent(android.net.Uri.parse("content://fixture/document"), "")
        assertEquals("application/octet-stream", intent.type)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            fileShareIntent(android.net.Uri.parse("file:///private/fixture"), "text/plain")
        }
    }

    @Test fun systemChooserOpensAndCancelsWithoutSending() {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "synthetic chooser probe")
            }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var opened = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val owner =
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.packageName
                    ?.toString()
            if (owner == "android" || owner == "com.android.intentresolver") {
                opened = true
                break
            }
            android.os.SystemClock.sleep(50)
        }
        assertTrue("System chooser did not open", opened)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }

    private fun receive(send: Intent): Intent {
        val action = context.packageName + ".share-receipt." + UUID.randomUUID()
        val result = AtomicReference<Intent?>()
        val latch = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    result.set(intent)
                    latch.countDown()
                }
            }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        try {
            context.startActivity(
                Intent(send).apply {
                    component =
                        ComponentName(instrumentation.context.packageName, "com.helix.app.test.ShareReceiptActivity")
                    putExtra("receiptAction", action)
                    putExtra("receiptPackage", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            assertTrue("Fixture did not receive the payload", latch.await(5000, TimeUnit.MILLISECONDS))
            return requireNotNull(result.get()).also {
                assertNotEquals(android.os.Process.myUid(), it.getIntExtra("receiverUid", -1))
                assertEquals(null, it.getStringExtra("failure"))
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
