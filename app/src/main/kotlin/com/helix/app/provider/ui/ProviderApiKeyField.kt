package com.helix.app.provider.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Display-only secret state, deliberately separate from the editable/persisted form. */
private class KeyRevealState {
    var visible by mutableStateOf(false)
    var secret by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var job: Job? = null
    var generation = 0

    fun hide() {
        generation++
        job?.cancel()
        job = null
        secret = null
        visible = false
        loading = false
        failed = false
    }
}

@Composable
// Explicit user reveal crosses a fallible storage boundary; failures render a safe message.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun ProviderApiKeyField(
    form: ProviderForm,
    onField: (ProviderForm) -> Unit,
    enabled: Boolean = true,
    loadStoredKey: suspend (String) -> String? = { null },
) {
    val reveal = remember(form.providerId, form.template.id) { KeyRevealState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(reveal, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) reveal.hide()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            reveal.hide()
        }
    }
    val showingStored = reveal.secret != null
    OutlinedTextField(
        enabled = enabled,
        value = reveal.secret ?: form.fields.apiKey,
        onValueChange = {
            if (!showingStored) onField(form.copy(fields = form.fields.copy(apiKey = it)))
        },
        readOnly = showingStored || reveal.loading,
        label = { Text("API Key") },
        placeholder = { if (form.hasStoredKey) Text("••••••••") },
        supportingText = {
            Text(
                stringResource(
                    when {
                        reveal.failed -> R.string.provider_models_operation_failed
                        showingStored -> R.string.provider_key_hide_to_replace
                        form.hasStoredKey -> R.string.provider_form_api_key_keep
                        else -> R.string.provider_key_optional_label
                    },
                ),
            )
        },
        isError = reveal.failed,
        singleLine = true,
        visualTransformation = if (reveal.visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                enabled = enabled,
                onClick = {
                    if (reveal.visible || reveal.loading) {
                        reveal.hide()
                    } else if (form.fields.apiKey.isNotEmpty() || !form.hasStoredKey || form.providerId == null) {
                        reveal.visible = true
                    } else {
                        reveal.failed = false
                        reveal.loading = true
                        val generation = ++reveal.generation
                        reveal.job =
                            scope.launch {
                                try {
                                    val value = loadStoredKey(form.providerId)
                                    if (generation == reveal.generation &&
                                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        reveal.secret = value
                                        reveal.visible = value != null
                                        reveal.failed = value == null
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (generation == reveal.generation) reveal.failed = true
                                } finally {
                                    if (generation == reveal.generation) {
                                        reveal.loading = false
                                        reveal.job = null
                                    }
                                }
                            }
                    }
                },
                modifier = Modifier.testTag("provider-form-key-visibility"),
            ) {
                Icon(
                    painterResource(
                        if (reveal.visible ||
                            reveal.loading
                        ) {
                            R.drawable.ic_visibility_off
                        } else {
                            R.drawable.ic_visibility
                        },
                    ),
                    stringResource(
                        if (reveal.visible ||
                            reveal.loading
                        ) {
                            R.string.provider_hide_api_key
                        } else {
                            R.string.provider_show_api_key
                        },
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("provider-form-key"),
    )
}
