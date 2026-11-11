@file:Suppress("FunctionName", "FunctionNaming")

package com.helix.app.search

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.tools.android.WebSearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SearchForm(
    val settings: WebSearchSettings,
    enabled: Boolean,
    savedKeys: Map<WebSearchProvider, Boolean>,
) {
    var savedKeys by mutableStateOf(savedKeys)
    var provider by mutableStateOf(settings.provider)
    var enabled by mutableStateOf(enabled)
    var hasKey by mutableStateOf(savedKeys[settings.provider] == true)
    var key by mutableStateOf("")
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

@Composable
internal fun WebSearchSettingsEntry() {
    val app = LocalContext.current.applicationContext as HelixApplication
    com.helix.app.ui.SettingsDisclosure(stringResource(R.string.web_search_title), "settings-web-search") {
        var form by remember { mutableStateOf<SearchForm?>(null) }
        var failed by remember { mutableStateOf(false) }
        var reload by remember { mutableStateOf(0) }
        LaunchedEffect(reload) {
            failed = false
            searchSettingsAttempt {
                val store = WebSearchSettings.create(app, app.awaitAppContainer().storage.secrets)
                SearchForm(store, store.enabled, WebSearchProvider.entries.associateWith(store::hasKey))
            }.onSuccess { form = it }.onFailure { failed = true }
        }
        Text(stringResource(R.string.web_search_help))
        val loaded = form
        when {
            loaded != null -> {
                SearchSettingsForm(loaded)
            }

            failed -> {
                Text(stringResource(R.string.web_search_save_error))
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.web_search_retry)) }
            }

            else -> {
                Text(stringResource(R.string.web_search_loading))
            }
        }
    }
}

@Composable
private fun SearchSettingsForm(form: SearchForm) {
    Row {
        WebSearchProvider.entries.forEach { provider ->
            TextButton(enabled = !form.busy, onClick = {
                form.provider = provider
                form.hasKey = form.savedKeys[provider] == true
                form.key = ""
                form.enabled = false
            }) {
                Text((if (form.provider == provider) "✓ " else "") + provider.name)
            }
        }
    }
    Row {
        Text(stringResource(R.string.web_search_enabled))
        Switch(enabled = !form.busy, checked = form.enabled, onCheckedChange = { form.enabled = it })
    }
    OutlinedTextField(
        value = form.key,
        onValueChange = { form.key = it },
        enabled = !form.busy,
        label = { Text(stringResource(if (form.hasKey) R.string.web_search_replace_key else R.string.web_search_key)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        modifier = Modifier.testTag("web-search-key"),
    )
    if (form.failed) Text(stringResource(R.string.web_search_save_error))
    SearchSettingsActions(form)
}

@Composable
private fun SearchSettingsActions(form: SearchForm) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedMessage = stringResource(R.string.web_search_saved)

    fun apply(remove: Boolean) {
        scope.launch {
            form.busy = true
            form.failed = false
            searchSettingsAttempt {
                if (remove) {
                    form.settings.removeKey(form.provider)
                } else {
                    form.settings.save(form.enabled, form.key, form.provider)
                }
                form.settings.enabled to form.settings.hasKey
            }.onSuccess { (enabled, hasKey) ->
                form.enabled = enabled
                form.hasKey = hasKey
                form.savedKeys = form.savedKeys + (form.provider to hasKey)
                form.key = ""
                android.widget.Toast
                    .makeText(context, savedMessage, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }.onFailure { form.failed = true }
            form.busy = false
        }
    }
    Button(
        enabled = !form.busy && (!form.enabled || form.hasKey || form.key.isNotBlank()),
        onClick = { apply(false) },
    ) { Text(stringResource(R.string.web_search_save)) }
    TextButton(enabled = !form.busy && form.hasKey, onClick = { apply(true) }) {
        Text(stringResource(R.string.web_search_remove_key))
    }
}

private suspend fun <T> searchSettingsAttempt(action: suspend () -> T): Result<T> =
    withContext(Dispatchers.IO) {
        runCatching { action() }.onFailure { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
        }
    }
