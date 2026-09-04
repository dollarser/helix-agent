package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.helix.app.R

/**
 * The first-launch privacy notice (HXA-028 “首次启动隐私说明”; ADR-0006: fresh
 * install / data reset → STANDARD + this flow). It blocks the main UI until the
 * user explicitly acknowledges; the caller persists the acknowledgement via
 * [com.helix.app.FirstLaunchStore]. The text states only what M2 actually does —
 * no capability is advertised before its milestone.
 */
@Composable
@Suppress("FunctionName")
fun FirstLaunchNoticeScreen(onContinue: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
                .testTag("first-launch-notice"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.notice_title), style = MaterialTheme.typography.headlineSmall)
        NOTICE_SECTIONS.forEach { section ->
            Text(
                text = stringResource(section),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Justify,
            )
        }
        Button(
            onClick = onContinue,
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
