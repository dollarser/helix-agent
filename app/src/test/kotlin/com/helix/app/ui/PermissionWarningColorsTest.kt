package com.helix.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionWarningColorsTest {
    @Test fun bothThemesExceedNormalTextContrast() {
        for (dark in listOf(false, true)) {
            val surface = (if (dark) darkColorScheme() else lightColorScheme()).surface
            val text = permissionWarningColor(dark)
            val ratio =
                (maxOf(surface.luminance(), text.luminance()) + 0.05) /
                    (minOf(surface.luminance(), text.luminance()) + 0.05)
            assertTrue("dark=$dark contrast=$ratio", ratio >= 4.5)
        }
    }
}
