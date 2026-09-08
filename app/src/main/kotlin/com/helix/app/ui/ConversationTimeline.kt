package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Following is presentation state: user scrolling never changes the running task. */
@Composable
@Suppress("FunctionName")
internal fun ConversationTimeline(
    sessionId: String?,
    contentVersion: Any?,
    modifier: Modifier = Modifier,
    followContent: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    key(sessionId, followContent) { FollowingTimeline(contentVersion, modifier, followContent, content) }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun FollowingTimeline(
    contentVersion: Any?,
    modifier: Modifier,
    followContent: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    var automaticScroll by remember { mutableStateOf(false) }
    val count by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val viewport by remember { derivedStateOf { state.layoutInfo.viewportEndOffset } }
    val connection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0) following = false
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.canScrollForward }.collect { (scrolling, hasLater) ->
            if (scrolling && !automaticScroll) following = false
            if (!scrolling && !hasLater) following = true
        }
    }
    LaunchedEffect(contentVersion, following, count, viewport) {
        if (followContent && following && count > 0) {
            automaticScroll = true
            try {
                // A trailing anchor also exposes the end of a message taller than the viewport.
                state.scrollToItem(count - 1)
            } finally {
                automaticScroll = false
            }
        }
    }
    Box(modifier) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("chat-timeline"),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
            item(key = "timeline-end") {
                Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("chat-timeline-end"))
            }
        }
        if (followContent && !following && state.canScrollForward) {
            FilledTonalButton(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).testTag("chat-scroll-latest"),
            ) {
                Text(stringResource(R.string.chat_scroll_latest))
            }
        }
    }
}
