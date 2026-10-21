package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedVerticalScroll

/** First-launch information. Acknowledgement is persisted without granting any capability. */
@Composable
@Suppress("FunctionName")
fun FirstLaunchNoticeScreen(onContinue: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .wrapContentWidth()
                .widthIn(max = com.helix.app.HelixUiDesign.formWidth)
                .indicatedVerticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("first-launch-notice"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.notice_title), style = MaterialTheme.typography.headlineMedium)
        NOTICE_SECTIONS.forEach { section ->
            Text(
                text = stringResource(section),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Button(
            onClick = onContinue,
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("first-launch-continue"),
        ) {
            Text(stringResource(R.string.notice_continue))
        }
    }
}

private val NOTICE_SECTIONS: List<Int> =
    listOf(
        R.string.notice_section_1,
        R.string.notice_section_2,
        R.string.notice_section_3,
        R.string.notice_section_4,
    )
