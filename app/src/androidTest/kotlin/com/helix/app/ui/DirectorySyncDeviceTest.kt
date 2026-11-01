package com.helix.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.app.R
import com.helix.app.files.DirectorySyncService
import com.helix.app.files.FileManagerService
import com.helix.app.files.ManualFileOperations
import com.helix.app.files.NioManualFileBackend
import com.helix.app.files.SyncRoot
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Private fixture directories only. Actual SAF provider behavior is separately tested in ManualSafFileDeviceTest. */
class DirectorySyncDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextRuleCanRunAndDisplayVerifiedResultWithoutRealTreeGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".asrvalidation"))
        val root = File(context.cacheDir, "sync-ui-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(root, "work/local").mkdirs()
            File(root, "remote").mkdirs()
            File(root, "work/local/a.txt").writeText("Synthetic UI content")
            val roots = ScopeRootResolver { root.toPath() }
            val backend = NioManualFileBackend("app", roots, false) {}
            val manual = ManualFileOperations({ backend }, { true })
            var data: String? = null
            val sync = DirectorySyncService(manual, { data }, { data = it }, { _, _ -> })
            val rule = sync.create(SyncRoot("app", "work/local"), SyncRoot("saf-synthetic", "remote"))
            val files = FileManagerService(WorkspaceArtifactStore(roots), roots, "app", manual = manual)
            files.directorySync = lazy { sync }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    HelixTheme { DirectorySyncDialog(files) {} }
                }
            }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("sync-now-${rule.id}"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("sync-now-${rule.id}")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(5000) { sync.list().single().status == "COMPLETE" }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText(context.getString(R.string.sync_complete)),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.sync_complete)).assertIsDisplayed()
            assertEquals("Synthetic UI content", File(root, "remote/a.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun profileRestoreRevokesOldAutomaticSyncWithoutTouchingFiles() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        require(original.packageName.endsWith(".asrvalidation"))
        val root = File(original.cacheDir, "sync-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val prefix = "sync-fixture-${UUID.randomUUID()}-"
        val stores = mutableSetOf<String>()
        val context =
            object : android.content.ContextWrapper(original) {
                override fun getNoBackupFilesDir(): File = File(root, "state").apply { mkdirs() }

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): android.content.SharedPreferences {
                    stores += prefix + name
                    return original.getSharedPreferences(prefix + name, mode)
                }
            }
        var sync: DirectorySyncService? = null
        var id: String? = null
        try {
            File(root, "left").mkdirs()
            File(root, "right").mkdirs()
            val backend = NioManualFileBackend("app", ScopeRootResolver { root.toPath() }, false) {}
            val manual = ManualFileOperations({ backend }, { true })
            val first =
                com.helix.app.files
                    .directorySyncService(context, manual)
            sync = first
            val rule = first.create(SyncRoot("app", "left"), SyncRoot("saf-synthetic", "right"))
            id = rule.id
            first.enable(rule.id, true)
            val snapshot =
                com.helix.app.backup.PortablePreferences
                    .capture(context)
            com.helix.app.backup.PortablePreferences
                .refreshLoaded(context, snapshot)
            val reopened =
                com.helix.app.files
                    .directorySyncService(context, manual)
            org.junit.Assert.assertFalse(reopened.list().single().enabled)
            assertEquals("REVIEW", reopened.list().single().status)
            assertEquals(emptyMap<String, com.helix.app.files.SyncNode>(), reopened.list().single().base)
        } finally {
            id?.let { sync?.enable(it, false) }
            stores.forEach { original.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
