package com.helix.app.eval

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.helix.app.chat.DisplayExecutionState
import com.helix.app.chat.DisplayExecutionUi
import com.helix.app.ui.BackgroundDisplayPreview
import com.helix.app.ui.LocalizedComposeActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackgroundPreviewUiDeviceTest {
    private var display by mutableStateOf(
        DisplayExecutionUi("preview", "turn", DisplayExecutionState.ACTIVE, generation = 1),
    )
    private val reads = AtomicInteger()
    private val image =
        Bitmap.createBitmap(240, 426, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        BackgroundDisplayPreview(display, readName = { "酷安" }) {
                            reads.incrementAndGet()
                            image
                        }
                    }
                }
            }
        }

    @After fun cleanup() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun livePreviewExpandsDragsHidesRestoresAndClearsOnRelease() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        val node = compose.onNodeWithTag("background-preview-image")
        node.assertIsDisplayed()
        val small = node.fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithText("酷安").assertIsDisplayed()
        val top =
            compose
                .onNodeWithTag("background-preview")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(node.fetchSemanticsNode().boundsInRoot.top - top < small * 0.4f)
        node.performClick()
        assertTrue(node.fetchSemanticsNode().boundsInRoot.width > small)
        compose
            .onNodeWithTag(
                "background-preview-drag",
            ).performTouchInput { swipe(center, center + Offset(-50f, -80f)) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        assertTrue(node.fetchSemanticsNode().boundsInRoot.width > small)
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-image").assertDoesNotExist()
        val pausedReads = reads.get()
        Thread.sleep(600)
        assertTrue(reads.get() <= pausedReads + 1)
        compose.onNodeWithTag("background-preview-reopen").performClick()
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.CONNECTION_LOST) }
        compose.onNodeWithTag("background-preview").assertDoesNotExist()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.ACTIVE, generation = 2) }
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.RELEASED) }
        compose.onNodeWithTag("background-preview").assertDoesNotExist()
    }
}
