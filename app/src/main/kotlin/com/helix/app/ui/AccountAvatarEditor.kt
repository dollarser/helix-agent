package com.helix.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.LocalUserProfile
import com.helix.app.account.LocalUserProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The picker confirms replacement; cancelled or failed imports preserve the current avatar. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun AccountAvatarEditor(
    profile: LocalUserProfile,
    store: LocalUserProfileStore,
    onBusy: (Boolean) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun save(
        uri: Uri? = null,
        remove: Boolean = false,
    ) {
        if (busy) return
        busy = true
        onBusy(true)
        failed = false
        scope.launch {
            try {
                store.save(uri, remove)
                showAccountToast(context, R.string.user_profile_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
                onBusy(false)
            }
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) save(uri)
        }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LocalProfileAvatar(profile.avatarFile, 96.dp)
        Row {
            TextButton({ picker.launch("image/*") }, Modifier.testTag("profile-photo"), enabled = !busy) {
                Text(stringResource(R.string.user_profile_choose_avatar))
            }
            if (profile.avatarFile != null) {
                TextButton({ save(remove = true) }, Modifier.testTag("profile-photo-remove"), enabled = !busy) {
                    Text(stringResource(R.string.user_profile_remove_avatar))
                }
            }
        }
        if (failed) Text(stringResource(R.string.user_profile_save_failed), Modifier.testTag("profile-error"))
    }
}
