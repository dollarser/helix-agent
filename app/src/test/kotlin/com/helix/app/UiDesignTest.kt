package com.helix.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class UiDesignTest {
    @Test
    fun bodyAndActionLabelsRemainReadableInBothThemes() {
        for (dark in listOf(false, true)) {
            val colors = HelixUiDesign.colors(dark)
            for ((foreground, background) in listOf(
                colors.onBackground to colors.background,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceContainerLow,
                colors.onPrimary to colors.primary,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onErrorContainer to colors.errorContainer,
            )) {
                assertTrue("Insufficient text contrast in dark=$dark", contrast(foreground, background) >= 4.5)
            }
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
