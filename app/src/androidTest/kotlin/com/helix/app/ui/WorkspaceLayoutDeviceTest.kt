package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
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

/** Bounded layout fixtures, not a claim that any device or screenshot acceptance has run. */
class WorkspaceLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chinese320LargeFont() = verify(320, AppLanguage.ZH_CN, 2f)

    @Test fun chinese360LargeFont() = verify(360, AppLanguage.ZH_CN, 2f)

    @Test fun chinese412LargeFont() = verify(412, AppLanguage.ZH_CN, 2f)

    @Test fun english320LargeFont() = verify(320, AppLanguage.EN, 2f)

    @Test fun english360LargeFont() = verify(360, AppLanguage.EN, 2f)

    @Test fun english412LargeFont() = verify(412, AppLanguage.EN, 2f)

    @Test fun defaultFont320() = verify(320, AppLanguage.ZH_CN, 1f)

    @Test fun defaultFont360() = verify(360, AppLanguage.EN, 1f)

    @Test fun defaultFont412() = verify(412, AppLanguage.ZH_CN, 1f)

    private fun verify(
        width: Int,
        language: AppLanguage,
        fontScale: Float,
    ) {
        val context =
            AppLanguageStore.wrapForLocale(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppLanguageStore.localeListFor(language),
            )
        val roots = ScopeRootResolver { context.cacheDir.toPath() }
        val files =
            FileManagerService(WorkspaceArtifactStore(roots), roots, "app", workspaceSources = {
                listOf(
                    FileSource(
                        "fixture",
                        "Long directory",
                        FileSourceKind.WORKSPACE,
                        false,
                        workspaceBackend = "SAF",
                        available = false,
                    ),
                )
            })
        var selected = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    Column(
                        Modifier
                            .width(width.dp)
                            .height(480.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("workspace-viewport"),
                    ) {
                        SessionWorkspaceSection(
                            files,
                            "scope:fixture:" + "long-directory/".repeat(12),
                            true,
                        ) { selected++ }
                    }
                }
            }
        }
        assertUnavailableAndSelect(context.getString(com.helix.app.R.string.workspace_unavailable))
        assertEquals(1, selected)
    }

    private fun assertUnavailableAndSelect(unavailable: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(unavailable, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose
            .onNodeWithText(unavailable, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val button = compose.onNodeWithTag("session-settings-directory").performScrollTo().assertIsDisplayed()
        val viewport = compose.onNodeWithTag("workspace-viewport").getUnclippedBoundsInRoot()
        val bounds = button.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        button.performClick()
    }
}
