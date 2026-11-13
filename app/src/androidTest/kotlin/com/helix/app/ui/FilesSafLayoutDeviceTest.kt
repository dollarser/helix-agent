package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.feature.files.SafTreeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real row measurement and click targeting only; never grants or revokes a system directory. */
class FilesSafLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longProviderNameKeepsRemoveVisibleAtNormalSize() = verify(1f)

    @Test fun longProviderNameKeepsRemoveVisibleWithLargeText() = verify(2f)

    private fun verify(fontScale: Float) {
        val source = SafTreeSource("saf-owned-layout", "primary_Documents_" + "Helix-reliability-目录-".repeat(15))
        var removed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) {
                        FilesSafSourceRow(source) { removed++ }
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("files-saf-source-${source.scopeId}").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("files-saf-remove-${source.scopeId}")
        action.assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= row.left && bounds.right <= row.right)
        assertTrue(bounds.top >= row.top && bounds.bottom <= row.bottom)
        action.performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
