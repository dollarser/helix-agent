package com.helix.feature.conversation.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SessionAccessDialogDeviceTest : ConversationDeviceTestFixture() {
    @get:Rule val compose = createComposeRule()
    private val source = SessionRowUi("source", "Same title", 0, false, null, null)
    private val target = SessionRowUi("target", "Same title", 0, false, null, null)

    @Test fun selectingAndEditingDoesNotGrantUntilSave() {
        var saved: SessionAccessSelection? = null
        compose.setContent {
            MaterialTheme {
                SessionAccessDialog(source, listOf(source, target), { emptyList() }, { saved = it }, {})
            }
        }
        compose.onNodeWithTag("session-access-target-source").assertDoesNotExist()
        compose.onNodeWithTag("session-access-save").assertIsNotEnabled()
        compose.onNodeWithTag("session-access-target-target").performClick()
        compose.onNodeWithTag("session-access-read").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("session-access-save").performClick()
        compose.runOnIdle { assertEquals(SessionAccessSelection("target", true, false), saved) }
    }

    @Test fun revokePersistsAllScopesOffOnlyOnSave() {
        var saved: SessionAccessSelection? = null
        compose.setContent {
            MaterialTheme {
                SessionAccessDialog(
                    source,
                    listOf(target),
                    { listOf(SessionAccessSelection("target", true, true, true)) },
                    { saved = it },
                    {},
                )
            }
        }
        compose.onNodeWithTag("session-access-target-target").performClick()
        compose.onNodeWithTag("session-access-revoke").performScrollTo().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("session-access-save").performClick()
        compose.runOnIdle { assertEquals(SessionAccessSelection("target", false, false), saved) }
    }

    @Test fun delegationCanBeGrantedWithoutHistoryOrMetadata() {
        var saved: SessionAccessSelection? = null
        compose.setContent {
            MaterialTheme {
                SessionAccessDialog(source, listOf(target), { emptyList() }, { saved = it }, {})
            }
        }
        compose.onNodeWithTag("session-access-target-target").performClick()
        compose.onNodeWithTag("session-access-delegate").performScrollTo().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("session-access-save").performClick()
        compose.runOnIdle { assertEquals(SessionAccessSelection("target", false, false, true), saved) }
    }

    @Test fun failedCreationLoadCannotGrantCreation() {
        compose.setContent {
            MaterialTheme {
                SessionAccessDialog(
                    source,
                    emptyList(),
                    { emptyList() },
                    {},
                    {},
                    loadCreation = { error("Unavailable") },
                    saveCreation = { error("Must not save") },
                )
            }
        }
        compose.onNodeWithTag("session-access-create").assertIsNotEnabled()
        compose.onNodeWithTag("session-access-save").assertIsNotEnabled()
    }
}
