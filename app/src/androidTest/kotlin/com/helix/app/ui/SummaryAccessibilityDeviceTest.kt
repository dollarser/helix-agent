package com.helix.app.ui

import android.content.Context
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SummaryAccessibilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishExpandedTextIsReadableAndControlsAreNamed() = verify(AppLanguage.EN)

    @Test fun chineseExpandedTextIsReadableAndControlsAreNamed() = verify(AppLanguage.ZH_CN)

    private fun verify(language: AppLanguage) {
        val context =
            AppLanguageStore.wrapForLocale(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppLanguageStore.localeListFor(language),
            )
        val text =
            (if (language == AppLanguage.EN) "Long goal and model name. " else "完整目标与模型名称。 ")
                .repeat(30) + "END"
        render(context, text)
        val summary = compose.onNodeWithTag("summary")
        val toggle = compose.onNodeWithTag("summary-toggle")
        val collapsed = summary.getUnclippedBoundsInRoot()
        toggle.assertContentDescriptionEquals(context.getString(R.string.summary_expand)).assertHasClickAction()
        toggle.assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        toggle.performClick()
        summary.assertTextEquals(text)
        toggle.assertContentDescriptionEquals(context.getString(R.string.summary_collapse))
        compose.onNodeWithTag("summary-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        val expanded = summary.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("summary-viewport").getUnclippedBoundsInRoot()
        assertTrue("Expanded text ends inside the reading viewport", expanded.bottom <= viewport.bottom)
        assertTrue("Expanded text remains taller than the viewport", expanded.top < viewport.top)
        toggle.performScrollTo().assertIsDisplayed().performClick()
        toggle.assertContentDescriptionEquals(context.getString(R.string.summary_expand))
        val restored = summary.getUnclippedBoundsInRoot()
        assertEquals(collapsed.bottom - collapsed.top, restored.bottom - restored.top)
    }

    private fun render(
        context: Context,
        text: String,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(
                        Modifier
                            .width(240.dp)
                            .height(280.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("summary-viewport"),
                    ) {
                        ExpandableSummary(text, MaterialTheme.typography.titleSmall, "summary", collapsedLines = 3)
                    }
                }
            }
        }
    }
}
