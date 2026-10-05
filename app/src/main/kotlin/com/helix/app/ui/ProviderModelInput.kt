package com.helix.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Catalog suggestions only; typing a model never triggers a network request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ProviderModelInput(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(models) { if (models.isNotEmpty()) expanded = true }
    val known = value in models
    val matches = models.filter { known || it.contains(value, ignoreCase = true) }.take(200)
    ExposedDropdownMenuBox(
        expanded = enabled && expanded && models.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(stringResource(R.string.provider_form_model_label)) },
            supportingText = { Text(stringResource(R.string.provider_model_input_hint)) },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            trailingIcon = {
                if (models.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded,
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled),
                    )
                }
            },
            modifier = modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled),
        )
        ExposedDropdownMenu(
            expanded = enabled && expanded && models.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            if (matches.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_models_no_match)) },
                    onClick = {},
                    enabled = false,
                )
            }
            matches.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        onValueChange(model)
                        expanded = false
                        keyboard?.hide()
                    },
                    modifier = Modifier.testTag("provider-model-option-$model"),
                )
            }
        }
    }
}
