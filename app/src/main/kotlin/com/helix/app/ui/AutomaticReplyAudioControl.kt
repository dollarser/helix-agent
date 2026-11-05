package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.VisibleReplyFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
// One UI mapping over opt-in, account and destination-consent states; no execution policy.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun AutomaticReplyAudioControl(reader: ReadAloudService?, frame: VisibleReplyFrame?) {
    val app = LocalContext.current.applicationContext as? HelixApplication ?: return
    if (reader == null) return
    val automatic by app.replyAudioSettings.automatic.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val identity by app.account.identityState.collectAsStateWithLifecycle()
    val signedIn by app.account.signedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val starts = reader.automaticStarts
    var confirm by remember(reader) { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    val accountReady = reader.accountIdentity == null || (signedIn && identity == reader.accountIdentity)
    val consentIdentity = reader.accountIdentity ?: "custom-service"
    val approved =
        !reader.uploadsText || reader.uploadDestination?.let {
            app.speechConsent.approved("tts", it, consentIdentity)
        } == true

    fun save(value: Boolean) {
        busy = true
        scope.launch {
            try {
                app.replyAudioSettings.setAutomatic(value)
                if (!value && reader.livePlaybackTurnId != null) reader.stop()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = R.string.reply_audio_save_failed
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(automatic, frame, accountReady, approved, revision, visible) {
        if ((!accountReady || !approved) && reader.livePlaybackTurnId != null) reader.stop()
        val enabled = automatic && accountReady && approved
        if (starts.start(frame, enabled && visible)) {
            reader.startLiveReply(requireNotNull(frame))
        }
    }
    IconToggleButton(automatic, onCheckedChange = { next ->
        when {
            !next -> save(false)
            !accountReady -> error = R.string.speech_account_required
            !approved -> confirm = true
            else -> save(true)
        }
    }, enabled = !busy, modifier = Modifier.testTag("chat-auto-read")) {
        Icon(
            painterResource(R.drawable.ic_read_aloud),
            stringResource(if (automatic) R.string.reply_audio_automatic_on else R.string.reply_audio_automatic_off),
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.read_aloud_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.cloud_tts_upload))
                    reader.uploadDestination?.let {
                        Text(
                            java.net
                                .URI(it)
                                .host
                                .orEmpty(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val granted =
                        reader.uploadDestination?.let {
                            app.speechConsent.approve("tts", it, consentIdentity)
                        } == true
                    if (granted) {
                        confirm = false
                        revision++
                        save(true)
                    } else {
                        error = R.string.speech_consent_failed
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    error?.let { errorLabel ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.read_aloud_title)) },
            text = { Text(stringResource(errorLabel)) },
            confirmButton = { TextButton({ error = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
