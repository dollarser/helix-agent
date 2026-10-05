package com.helix.app.automation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.mobileuse.automation.AutomationApplicationCatalog
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Global plugin configuration; saving never selects a conversation or grants Android permissions. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun MobileUseSettings(
    context: Context,
    store: MobileUseGrantStore,
    onPermissions: () -> Unit,
) {
    var packages by remember { mutableStateOf(emptySet<String>()) }
    var wholePhone by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val applications = remember(context) { AutomationApplicationCatalog(context) }
    LaunchedEffect(store) {
        try {
            val config = withContext(Dispatchers.IO) { store.globalConfiguration() }
            packages = config?.scope?.allowedPackages.orEmpty()
            wholePhone = config?.scope?.allApplications == true
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    if (picker) {
        AutomationApplicationPicker(
            initialSelection = packages,
            loadApplications = applications::load,
            onConfirm = {
                packages = it
                picker = false
                saved = false
            },
            onDismiss = { picker = false },
        )
    }
    Column(Modifier.testTag("mobile-use-global-settings")) {
        Text(stringResource(R.string.mobile_use_global_help))
        Row {
            Checkbox(
                wholePhone,
                {
                    wholePhone = it
                    saved = false
                },
                enabled = loaded && !busy,
                modifier = Modifier.testTag("automation-all-applications"),
            )
            Text(stringResource(R.string.automation_whole_phone_label))
        }
        OutlinedButton(
            { picker = true },
            enabled = loaded && !busy && !wholePhone,
            modifier = Modifier.testTag("automation-select-apps"),
        ) {
            Text(stringResource(R.string.automation_app_selection_count, packages.size))
        }
        Text(stringResource(R.string.automation_image_handling))
        OutlinedButton(
            onClick = {
                val selected = packages.toSet()
                val all = wholePhone
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.configureGlobal(selected, all) }
                        saved = true
                        failed = false
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: RuntimeException) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = loaded && !busy && (wholePhone || packages.isNotEmpty()),
            modifier = Modifier.testTag("mobile-use-save-settings"),
        ) {
            Text(stringResource(R.string.mobile_use_save_settings))
        }
        if (saved) Text(stringResource(R.string.mobile_use_saved))
        if (failed) Text(stringResource(R.string.automation_start_failed))
        OutlinedButton(onPermissions, modifier = Modifier.testTag("mobile-use-open-permissions")) {
            Text(stringResource(R.string.settings_system_permissions_title))
        }
    }
}
