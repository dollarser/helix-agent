package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.app.files.FileSourceKind
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Dialog interaction and screenshots; real cleanup/recovery are covered by the storage fixtures. */
class WorkspaceCleanupDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chineseDefault() = verify(AppLanguage.ZH_CN, 1f)

    @Test fun chineseLarge() = verify(AppLanguage.ZH_CN, 2f)

    @Test fun englishDefault() = verify(AppLanguage.EN, 1f)

    @Test fun englishLarge() = verify(AppLanguage.EN, 2f)

    @Test fun systemDefault() = verify(AppLanguage.SYSTEM, 1f)

    @Test fun systemLarge() = verify(AppLanguage.SYSTEM, 2f)

    @Suppress("LongMethod") // One dialog crosses cancel, fixed-target confirmation and failure presentation.
    private fun verify(language: AppLanguage, scale: Float) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val context = AppLanguageStore.wrapForLocale(app, AppLanguageStore.localeListFor(language))
        val calls = CopyOnWriteArrayList<String>()
        var fail = false
        val roots = ScopeRootResolver { app.cacheDir.toPath() }
        val files =
            FileManagerService(WorkspaceArtifactStore(roots), roots, "app", workspaceCleanup = {
                calls += it
                if (fail) error("Synthetic cleanup refusal")
            })
        val state = FilesScreenState(files)
        val target =
            FileSource("original", "Workspace / " + "long-directory/".repeat(8), FileSourceKind.WORKSPACE, true)
        state.cleanupTarget = target
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, scale),
            ) {
                MaterialTheme {
                    val container = app.appContainer
                    WorkspaceCleanupDialog(
                        state,
                        FilesScreenActions(
                            state,
                            files,
                            container.safTree,
                            container.featureFiles,
                            rememberCoroutineScope(),
                            context,
                            context.resources,
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag("files-workspace-cleanup-confirm").assertIsDisplayed().assertIsEnabled()
        screenshot(app, language, scale)
        compose.onNodeWithText(context.getString(R.string.files_close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(null, state.cleanupTarget) }
        assertTrue(calls.isEmpty())
        compose.runOnIdle {
            state.cleanupTarget = target
            state.selectedScopeId = "newly-browsed-directory"
        }
        compose.onNodeWithTag("files-workspace-cleanup-confirm").performClick()
        compose.waitUntil(10_000) { state.cleanupTarget == null }
        assertEquals(listOf("original"), calls.toList())
        assertEquals(context.getString(R.string.files_workspace_cleanup_done), state.status)
        compose.runOnIdle {
            fail = true
            state.cleanupTarget = target
        }
        compose.onNodeWithTag("files-workspace-cleanup-confirm").performClick()
        compose.waitUntil(10_000) { state.cleanupTarget == null }
        assertEquals(listOf("original", "original"), calls.toList())
        assertEquals(context.getString(R.string.files_workspace_cleanup_failed), state.status)
    }

    private fun screenshot(
        app: HelixApplication,
        language: AppLanguage,
        scale: Float,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val width = InstrumentationRegistry.getArguments().getString("workspaceWidth", "unspecified")
        val directory = File(app.filesDir, "hxa210-ui-evidence").apply { check(mkdirs() || isDirectory) }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "cleanup-$width-${language.name}-$scale.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
