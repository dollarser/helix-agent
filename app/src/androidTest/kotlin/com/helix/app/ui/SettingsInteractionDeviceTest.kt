package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic composables only: does not reset or access the installed application's database. */
class SettingsInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidInputDoesNotSaveAndEditingClearsTheStaleError() {
        val saved = mutableListOf<Long>()
        render { saved += it }
        replace("")
        compose.onNodeWithTag("budget-save").performClick()
        compose.onNodeWithTag("budget-error", useUnmergedTree = true).assertIsDisplayed()
        replace("2000")
        compose.onNodeWithTag("budget-error", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(emptyList<Long>(), saved)
        compose.onNodeWithTag("budget-save").performClick()
        compose.onNodeWithTag("budget-saved").assertIsDisplayed()
        assertEquals(listOf(2000L), saved)
        replace("3000")
        compose.onNodeWithTag("budget-saved").assertDoesNotExist()
        assertEquals(listOf(2000L), saved)
    }

    @Test fun outOfRangeAndOverflowAreRejectedWhileBothBoundsCanSave() {
        val saved = mutableListOf<Long>()
        render { saved += it }
        for (value in listOf("0", "128001", "9999999999999999999999999999")) {
            replace(value)
            compose.onNodeWithTag("budget-save").performClick()
            compose.onNodeWithTag("budget-error", useUnmergedTree = true).assertIsDisplayed()
        }
        assertEquals(emptyList<Long>(), saved)
        for (value in listOf("1", "128000")) {
            replace(value)
            compose.onNodeWithTag("budget-save").performClick()
            compose.onNodeWithTag("budget-error", useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals(listOf(1L, 128000L), saved)
    }

    @Test fun rejectedSaveDoesNotClaimSuccess() {
        render { throw IllegalArgumentException("Rejected by configuration owner") }
        compose.onNodeWithTag("budget-save").performClick()
        compose.onNodeWithTag("budget-error", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("budget-saved").assertDoesNotExist()
    }

    @Test fun recreationRetainsUnsavedInputWithoutApplyingIt() {
        val saved = mutableListOf<Long>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { RunControlOutputEditor(16384) { saved += it } } }
        replace("2000")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2000").assertIsDisplayed()
        assertEquals(emptyList<Long>(), saved)
        compose.onNodeWithTag("budget-save").performClick()
        assertEquals(listOf(2000L), saved)
    }

    @Test fun disclosureHeadersExpandIndependentlyAndOnlyChangePresentation() {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    SettingsDisclosure("First", "first") { androidx.compose.material3.Text("First details") }
                    SettingsDisclosure("Second", "second") { androidx.compose.material3.Text("Second details") }
                }
            }
        }
        compose.onNodeWithText("First details").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("first").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        compose.onNodeWithTag("first").performClick()
        compose.onNodeWithTag("second").performClick()
        compose.onNodeWithText("First details").assertIsDisplayed()
        compose.onNodeWithText("Second details").assertIsDisplayed()
        compose.onNodeWithTag("first").performClick()
        compose.onNodeWithText("First details").assertDoesNotExist()
        compose.onNodeWithText("Second details").assertIsDisplayed()
    }

    private fun render(save: (Long) -> Unit) {
        compose.setContent { MaterialTheme { RunControlOutputEditor(16384, save) } }
    }

    private fun replace(value: String) {
        compose.onNodeWithTag("budget-output").performTextClearance()
        if (value.isNotEmpty()) compose.onNodeWithTag("budget-output").performTextInput(value)
    }
}
