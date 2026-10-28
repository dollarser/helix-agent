package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures only; compiling these tests does not establish device acceptance. */
class ConversationMenuRowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longSelectionWrapsWithinNarrowParentAndWholeRowIsTheTarget() {
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp).testTag("row-parent")) {
                        ConversationMenuRow(
                            title = "一个很长的专家名称，用来检查窄屏与大字体的换行",
                            supporting = "指令摘要与会话范围应在选用之前可读，不挤占右侧选中标记。",
                            selected = true,
                            onClick = { clicks++ },
                            modifier = Modifier.testTag("choice"),
                        )
                    }
                }
            }
        }
        val parent = compose.onNodeWithTag("row-parent").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("choice").assertIsSelected().getUnclippedBoundsInRoot()
        assertEquals(parent.left, row.left)
        assertEquals(parent.right, row.right)
        assertTrue(row.bottom - row.top >= 48.dp)
        compose.onNodeWithTag("choice").performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText("一个很长的专家名称，用来检查窄屏与大字体的换行").assertIsDisplayed()
    }
}
