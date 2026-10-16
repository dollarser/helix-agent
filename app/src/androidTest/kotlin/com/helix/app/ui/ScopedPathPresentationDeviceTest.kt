package com.helix.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScopedPathPresentationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullReferenceCanBeCopiedAndChangingDirectoryClosesItsDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val original = "scope:workspace-one:" + "directory/".repeat(12) + "报告.txt"
        val reference = mutableStateOf(original)
        val prior = clipboard.primaryClip
        try {
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ScopedPathText(reference.value, "项目资料")
                    }
                }
            }
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.workspace_location_details)).performClick()
            compose.onNodeWithText(original).assertExists()
            compose.onNodeWithText(context.getString(R.string.workspace_copy_location)).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(
                    original,
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            }
            compose.runOnIdle { reference.value = "scope:workspace-two:other/报告.txt" }
            compose.onNodeWithText(context.getString(R.string.workspace_copy_location)).assertDoesNotExist()
            compose.onNodeWithText("项目资料 / other/报告.txt").performScrollTo().assertIsDisplayed()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (prior == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(prior)
            }
        }
    }
}
