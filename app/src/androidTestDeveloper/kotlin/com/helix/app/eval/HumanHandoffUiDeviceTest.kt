package com.helix.app.eval

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.chat.HumanHandoff
import com.helix.app.chat.HumanHandoffState
import com.helix.app.ui.HumanControlPanel
import com.helix.app.ui.LocalizedComposeActivity
import com.helix.core.model.TurnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class HumanHandoffUiDeviceTest {
    private val c get() = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
    private var turn by mutableStateOf("")
    private var session: String? = null
    private var original: String? = null

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                MaterialTheme {
                    val screen by c.chatService.screen.collectAsState()
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        if (turn.isNotBlank()) HumanControlPanel(c.chatService, screen, turn)
                    }
                }
            }
        }

    @After fun restore() {
        LocalizedComposeActivity.content = {}
        session?.let(c.storage::deleteSessionPermanently)
        original?.let(c.chatService::openSession) ?: c.chatService.closeSession()
    }

    @Test fun manualNoteSurvivesRecreationAndUserFinishedNeverCallsModel() {
        original = c.chatService.screen.value.openSessionId
        val id = "human-ui-${UUID.randomUUID()}"
        session = id
        val now = System.currentTimeMillis()
        c.storage.sessions.create(id, "Synthetic manual control", null, null, now)
        val row = c.storage.turns.start(id, id, now)
        val stopping = c.storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
        c.storage.turns.updateState(stopping, TurnState.CANCELLED, row.stepCount, System.currentTimeMillis(), null)
        HumanHandoff(c.storage) { System.currentTimeMillis() }.record(id, HumanHandoffState.REQUESTED)
        c.chatService.openSession(id)
        compose.waitUntil(10_000) { c.chatService.screen.value.openSessionId == id }
        compose.runOnIdle { turn = id }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("已处理内容", substring = true),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("已处理内容（可选）").performTextInput("Synthetic manual step complete")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("Synthetic manual step complete"),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithText("Synthetic manual step complete", useUnmergedTree = true)
            .assertTextContains("Synthetic manual step complete")
            .assertIsDisplayed()
        saveSyntheticScreenshot()
        compose.onNodeWithText("我已全部完成").performClick()
        compose.waitUntil(10_000) {
            HumanHandoff(c.storage) { System.currentTimeMillis() }.state(id) == HumanHandoffState.FINISHED
        }
        assertEquals(
            1,
            c.storage.turns
                .listBySession(id)
                .size,
        )
        assertEquals(
            0,
            c.storage.modelCalls
                .listByTurn(id)
                .size,
        )
    }

    private fun saveSyntheticScreenshot() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val image =
            requireNotNull(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .uiAutomation
                    .takeScreenshot(),
            )
        java.io
            .File(app.filesDir, "closed-loop-eval/human-control.png")
            .apply { parentFile!!.mkdirs() }
            .outputStream()
            .use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        image.recycle()
    }
}
