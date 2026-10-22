package com.helix.app.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.SessionModelSelectionResult
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.BuiltInModelCatalog
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.core.model.ReasoningEffort
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI fixtures: no installed user settings, credentials, network or inference. */
class ModelPickerSaveDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val id = BuiltInModel.PROVIDER_ID
    private val model = BuiltInModel.MODEL_ID

    private fun reveal(tag: String) {
        compose.onNodeWithTag("chat-model-list").performScrollToNode(hasTestTag(tag))
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun clickOutside() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent
                    .obtain(downTime, SystemClock.uptimeMillis(), action, 5f, 200f, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                check(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        compose.waitForIdle()
    }

    private val row =
        ProviderRowUi(
            id,
            "Fixture",
            null,
            BuiltInModel.API_ROOT,
            ProviderResidence.PUBLIC_CLOUD,
            model,
            false,
            false,
            ConnectionTestStatus.Untested,
            BuiltInModel.capabilities,
            null,
            provisioning = ProviderProvisioningKind.BUNDLED_SERVICE,
            builtInCatalog = BuiltInModelCatalog(1, listOf(model, "future")),
        )

    @Test fun pickingAndReasoningWaitForSaveAndCommitTogether() {
        var saved: Triple<String, String, ReasoningEffort>? = null
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), null, null, true, { p, m, r ->
                    saved = Triple(p, m, r)
                    SessionModelSelectionResult.APPLIED
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-$id-$model")
        compose.onNodeWithTag("chat-model-$id-$model").performClick()
        compose.onNodeWithTag("chat-model-search").assertExists()
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-high").performClick()
        compose.runOnIdle { assertEquals(null, saved) }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.waitForIdle()
        assertEquals(Triple(id, model, ReasoningEffort.HIGH), saved)
        compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
    }

    @Test fun cancelAndBackDiscardEditsAndReopenSavedSelection() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), id, model, true, { _, _, _ ->
                    saves++
                    SessionModelSelectionResult.APPLIED
                }, reasoning = ReasoningEffort.HIGH)
            }
        }
        repeat(3) { attempt ->
            compose.onNodeWithTag("chat-model-menu").performClick()
            reveal("chat-model-$id-future")
            compose.onNodeWithTag("chat-model-$id-future").performClick()
            when (attempt) {
                0 -> {
                    compose.onNodeWithTag("chat-model-picker-cancel").performClick()
                }

                1 -> {
                    pressBack()
                }

                else -> {
                    clickOutside()
                }
            }
            compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
            compose.onNodeWithTag("chat-model-menu").performClick()
            reveal("chat-model-$id-$model")
            compose.onNodeWithTag("chat-model-$id-$model").assertIsSelected()
            compose.onNodeWithTag("chat-reasoning-menu").performClick()
            compose.onNodeWithTag("chat-reasoning-high").assertIsSelected()
            pressBack()
            compose.onNodeWithTag("chat-model-picker-cancel").performClick()
        }
        assertEquals(0, saves)
    }

    @Test fun pendingSelectionSurvivesRecreationWithoutApplying() {
        var saved: ReasoningEffort? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), null, null, true, { _, _, r ->
                    saved = r
                    SessionModelSelectionResult.APPLIED
                }, sessionKey = "same-session")
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-$id-$model")
        compose.onNodeWithTag("chat-model-$id-$model").performClick()
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-low").performClick()
        restoration.emulateSavedInstanceStateRestore()
        reveal("chat-model-$id-$model")
        compose.onNodeWithTag("chat-model-$id-$model").assertIsSelected()
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-low").assertIsSelected()
        pressBack()
        assertEquals(null, saved)
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.waitForIdle()
        assertEquals(ReasoningEffort.LOW, saved)
    }

    @Test fun catalogRefreshCannotReplacePendingSelectionAndSessionChangeDiscardsIt() {
        val rows = mutableStateOf(listOf(row))
        val session = mutableStateOf("original")
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(rows.value, null, null, true, { _, _, _ ->
                    saves++
                    SessionModelSelectionResult.APPLIED
                }, sessionKey = session.value)
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-$id-$model")
        compose.onNodeWithTag("chat-model-$id-$model").performClick()
        compose.runOnIdle { rows.value = listOf(row.copy(builtInCatalog = BuiltInModelCatalog(2, listOf("future")))) }
        compose.onNodeWithTag("chat-model-search").assertExists()
        compose.onNodeWithTag("chat-model-picker-save").assertIsNotEnabled()
        compose.runOnIdle { session.value = "replacement" }
        compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-$id-future")
        compose.onNodeWithTag("chat-model-$id-future").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-picker-save").assertIsNotEnabled()
        assertEquals(0, saves)
    }

    @Test fun savingBlocksDuplicateSaveAndBackUntilTheActualReceipt() {
        val receipt = CompletableDeferred<SessionModelSelectionResult>()
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), id, model, true, { _, _, _ ->
                    saves++
                    receipt.await()
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.onNodeWithTag("chat-model-picker-save").assertIsNotEnabled()
        compose.onNodeWithTag("chat-model-picker-cancel").assertIsNotEnabled()
        pressBack()
        compose.onNodeWithTag("chat-model-search").assertExists()
        assertEquals(1, saves)
        compose.runOnIdle { receipt.complete(SessionModelSelectionResult.BUSY) }
        reveal("chat-model-save-error")
        compose.onNodeWithTag("chat-model-save-error").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-picker-cancel").performClick()
    }

    @Test fun unexpectedSaveFailureStaysOpenAndCanBeRetried() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), id, model, true, { _, _, _ ->
                    saves++
                    if (saves == 1) error("Fixture failure")
                    SessionModelSelectionResult.APPLIED
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        reveal("chat-model-save-error")
        compose.onNodeWithTag("chat-model-save-error").assertIsDisplayed()
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
        assertEquals(2, saves)
    }

    @Test fun oldSessionSaveReceiptsCannotCloseOrFailANewlyOpenedDialog() {
        val session = mutableStateOf("original")
        val receipts = List(2) { CompletableDeferred<SessionModelSelectionResult>() }
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), id, model, true, { _, _, _ ->
                    receipts[saves++].await()
                }, sessionKey = session.value)
            }
        }
        listOf(SessionModelSelectionResult.APPLIED, SessionModelSelectionResult.FAILED).forEachIndexed {
            index,
            result,
            ->
            compose.onNodeWithTag("chat-model-menu").performClick()
            compose.onNodeWithTag("chat-model-picker-save").performClick()
            compose.runOnIdle { session.value = "replacement-$index" }
            compose.onNodeWithTag("chat-model-search").assertDoesNotExist()
            compose.onNodeWithTag("chat-model-menu").performClick()
            compose.runOnIdle { receipts[index].complete(result) }
            compose.onNodeWithTag("chat-model-search").assertExists()
            compose.onNodeWithTag("chat-model-save-error").assertDoesNotExist()
            compose.onNodeWithTag("chat-model-picker-save").assertIsEnabled()
            compose.onNodeWithTag("chat-model-picker-cancel").performClick()
        }
        assertEquals(2, saves)
    }
}
