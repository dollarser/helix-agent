package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class ScrollFeedbackDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lazyManagementListRetainsNativeScrollToIndex() {
        compose.setContent {
            MaterialTheme {
                IndicatedLazyColumn(Modifier.heightIn(max = 240.dp).testTag("management-list")) {
                    items(200, key = { it }) { Text("Model $it", Modifier.height(48.dp)) }
                }
            }
        }
        compose.onNodeWithTag("management-list").performScrollToIndex(199)
        compose.onNodeWithText("Model 199").assertIsDisplayed()
    }

    @Test fun tallFormRetainsNativeBringIntoView() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.heightIn(max = 240.dp).indicatedVerticalScroll(rememberScrollState())) {
                    repeat(30) { Text("Field $it", Modifier.height(48.dp)) }
                }
            }
        }
        compose.onNodeWithText("Field 29").performScrollTo().assertIsDisplayed()
    }
}
