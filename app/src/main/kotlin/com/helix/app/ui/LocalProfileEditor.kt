package com.helix.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.LocalUserAvatarImporter
import com.helix.app.account.LocalUserProfile
import com.helix.app.account.LocalUserProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.helix.ui.shared.R as SharedR

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun LocalProfileEditor(
    profile: LocalUserProfile,
    store: LocalUserProfileStore,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(profile.displayName.orEmpty()) }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var removed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                busy = true
                failed = false
                scope.launch {
                    try {
                        preview =
                            withContext(Dispatchers.IO) {
                                val bytes = LocalUserAvatarImporter(resolver).import(uri)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                            }
                        selected = uri
                        removed = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
    UserPageFrame(stringResource(R.string.user_profile_edit), "profile-editor", { if (!busy) onDismiss() }) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LocalProfileAvatar(
                    profile.avatarFile.takeUnless { removed || selected != null },
                    96.dp,
                    preview = preview,
                )
                Row {
                    TextButton(
                        { picker.launch("image/*") },
                        enabled = !busy,
                        modifier = Modifier.testTag("profile-photo"),
                    ) {
                        Text(stringResource(R.string.user_profile_choose_avatar))
                    }
                    if (profile.avatarFile != null || selected != null) {
                        TextButton({
                            selected = null
                            preview = null
                            removed = true
                        }, enabled = !busy, modifier = Modifier.testTag("profile-photo-remove")) {
                            Text(stringResource(R.string.user_profile_remove_avatar))
                        }
                    }
                }
            }
            val valid = isProfileNameValid(name)
            OutlinedTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth().testTag("profile-name"),
                label = { Text(stringResource(R.string.user_profile_name)) },
                singleLine = true,
                enabled = !busy,
                isError = !valid,
                supportingText = { Text(stringResource(R.string.user_profile_name_hint)) },
            )
            Text(
                stringResource(R.string.user_profile_local_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed) Text(stringResource(R.string.user_profile_save_failed), Modifier.testTag("profile-error"))
            Button({
                busy = true
                failed = false
                scope.launch {
                    try {
                        store.save(name, selected, removed)
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth().testTag("profile-save")) {
                Text(stringResource(if (busy) R.string.account_busy else SharedR.string.common_save))
            }
        }
    }
}

private fun isProfileNameValid(name: String): Boolean =
    name.codePointCount(0, name.length) <= 40 &&
        name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
