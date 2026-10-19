package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.voice.ReadAloudPhase
import com.helix.app.voice.ReadAloudService

@Composable
@Suppress("FunctionName")
internal fun ReadAloudLifecycle(
    service: ReadAloudService?,
    sessionId: String?,
) {
    val context = LocalContext.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { service?.leave() }
    androidx.compose.runtime.DisposableEffect(sessionId, service) {
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent,
                ) {
                    if (intent.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) service?.stop()
                }
            }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            service?.leave()
            context.unregisterReceiver(receiver)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod") // Local resource/player UI state mapping.
internal fun ReadAloudAction(
    id: String,
    text: String,
    supplied: ReadAloudService? = null,
) {
    val service = supplied ?: (LocalContext.current.applicationContext as? HelixApplication)?.readAloud ?: return
    val state by service.state.collectAsStateWithLifecycle()
    val bytes by service.model.downloadedBytes.collectAsStateWithLifecycle()
    var details by remember(id) { mutableStateOf(false) }
    val active =
        state.messageId == id && state.phase in
            setOf(ReadAloudPhase.PREPARING, ReadAloudPhase.SPEAKING, ReadAloudPhase.STOPPING)
    LaunchedEffect(state) {
        if (state.messageId == id && state.phase in
            setOf(ReadAloudPhase.MISSING, ReadAloudPhase.FAILED, ReadAloudPhase.UNAVAILABLE)
        ) {
            details = true
        }
    }
    IconButton(
        onClick = { if (active) service.stop() else service.play(id, text) },
        enabled = text.isNotBlank() && state.phase !in setOf(ReadAloudPhase.DOWNLOADING, ReadAloudPhase.STOPPING),
        modifier = Modifier.testTag("chat-read-$id"),
    ) {
        Icon(
            painterResource(if (active) R.drawable.ic_composer_stop else R.drawable.ic_read_aloud),
            stringResource(if (active) R.string.read_aloud_stop else R.string.read_aloud_start),
        )
    }
    if (details) {
        ConversationSheet(stringResource(R.string.read_aloud_title), "read-aloud", {
            service.leave()
            details = false
        }) {
            Column {
                Text(stringResource(R.string.read_aloud_local))
                Text(stringResource(R.string.read_aloud_lifecycle))
                if (state.phase == ReadAloudPhase.FAILED) Text(stringResource(R.string.read_aloud_failed))
                if (state.phase == ReadAloudPhase.UNAVAILABLE) Text(stringResource(R.string.read_aloud_unavailable))
                if (state.phase == ReadAloudPhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { (bytes ?: 0).toFloat() / service.model.totalBytes })
                    Text(stringResource(R.string.read_aloud_downloading, bytes ?: 0, service.model.totalBytes))
                    TextButton({ service.leave() }, Modifier.testTag("read-aloud-download-cancel")) {
                        Text(stringResource(R.string.read_aloud_cancel_download))
                    }
                } else if (state.phase == ReadAloudPhase.READY) {
                    Text(stringResource(R.string.read_aloud_ready))
                } else if (state.phase == ReadAloudPhase.MISSING ||
                    (state.phase == ReadAloudPhase.FAILED && state.messageId == null)
                ) {
                    TextButton({ service.download() }, Modifier.testTag("read-aloud-download")) {
                        Text(stringResource(R.string.read_aloud_download))
                    }
                }
            }
        }
    }
}
