from pathlib import Path
base = Path('app/src/main/kotlin/com/helix/app/ui')
p = base / 'ProviderFormDialog.kt'
s = p.read_text().replace('import androidx.compose.runtime.remember\n', 'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.mutableStateOf\n')
s = s.replace('    val error: SaveResult.Rejected?,\n', '    val error: SaveResult.Rejected?,\n    val selectedModels: Set<String> = emptySet(),\n    val preservedHeaders: Map<String, String> = emptyMap(),\n')
s = s.replace('if (template.credentialRequired)', 'if (false)')
s = s.replace('    onDismiss: () -> Unit,\n) {\n    val cleartext', '    onDismiss: () -> Unit,\n    discovery: List<String> = emptyList(),\n    discovering: Boolean = false,\n    discoveryMessage: Int? = null,\n    onDiscover: () -> Unit = {},\n) {\n    var advanced by remember { mutableStateOf(false) }\n    val cleartext')
start = s.index('    val keyOk =')
end = s.index('    AlertDialog(', start)
s = s[:start] + '    val saveEnabled = !saving && !discovering && (cleartext == null || form.cleartextConfirmed)\n' + s[end:]
start = s.index('                OutlinedTextField(\n                    value = form.fields.model')
end = s.index('                if (cleartext != null)', start)
old = s[start:end]
key = old[old.index('                    OutlinedTextField(\n                        value = form.fields.apiKey'):]
key = key[:key.rfind('                }')]
key = key.replace('"API Key"', 'stringResource(R.string.provider_key_optional_label)')
model = old[:old.index('                OutlinedTextField(\n                    value = form.fields.headerName')]
headers = old[old.index('                OutlinedTextField(\n                    value = form.fields.headerName'):old.index('                if (form.template.credentialRequired)')]
new = key + '''                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("provider-form-advanced")) {
                    Text(stringResource(R.string.provider_advanced_options))
                }
                if (advanced) {
''' + headers + '                }\n' + model + '''                TextButton(onClick = onDiscover, enabled = !discovering && !saving && (cleartext == null || form.cleartextConfirmed), modifier = Modifier.testTag("provider-discover-models")) {
                    Text(stringResource(if (discovering) R.string.provider_discovering_models else R.string.provider_discover_models))
                }
                discoveryMessage?.let { Text(stringResource(it)) }
                discovery.forEach { model ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = model in form.selectedModels,
                            onCheckedChange = { checked ->
                                onField(form.copy(selectedModels = if (checked) form.selectedModels + model else form.selectedModels - model))
                            },
                            modifier = Modifier.testTag("provider-model-choice-$model"),
                        )
                        Text(model, Modifier.weight(1f))
                    }
                }
'''
s = s[:start] + new + s[end:]
s = s.replace('    error = null,\n)', '    error = null,\n    selectedModels = row.backendModels.orEmpty().toSet(),\n    preservedHeaders = config.headers,\n)')
s = s.replace('    val headers =\n', '    val headers = form.preservedHeaders +\n')
s = s.replace('            form.template,', '            form.template.copy(credentialRequired = false, defaultHeaders = emptyMap()),')
s = s.replace('            form.fields.model.trim(),', '            form.fields.model.trim().ifEmpty { form.selectedModels.firstOrNull().orEmpty() },')
s = s.replace('                    if (form.providerId == null) {\n                        providerService.create(draft, key, form.cleartextConfirmed)\n                    } else {\n                        providerService.update(form.providerId, draft, key, form.cleartextConfirmed)\n                    }', '''                    val id = if (form.providerId == null) {
                        providerService.create(draft, key, form.cleartextConfirmed)
                    } else {
                        providerService.update(form.providerId, draft, key, form.cleartextConfirmed)
                        form.providerId
                    }
                    providerService.saveSelectedModels(id, (listOf(draft.model) + form.selectedModels).distinct())''')
p.write_text(s)

p = base / 'ProviderScreen.kt'
s = p.read_text().replace('    var deleteFailure', '''    var group by remember { mutableStateOf<ProviderProvisioningKind?>(null) }
    var discovery by remember { mutableStateOf<List<String>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf<Int?>(null) }
    var deleteFailure''', 1)
start = s.index('            OutlinedButton(\n                onClick = { templatePickerOpen')
end = s.index('        if (deleteFailure)', start)
s = s[:start] + '''        }
        if (group == null) {
            listOf(ProviderProvisioningKind.ON_DEVICE_ASSET, ProviderProvisioningKind.USER_CONFIGURED, ProviderProvisioningKind.MANAGED_ACCOUNT).forEach { category ->
                OutlinedButton(onClick = { group = category }, modifier = Modifier.fillMaxWidth().testTag("provider-group-${category.name}")) {
                    Text(stringResource(providerGroupLabel(category)))
                }
            }
        } else {
            TextButton(onClick = { group = null }, modifier = Modifier.testTag("provider-groups-back")) { Text(stringResource(R.string.provider_groups_back)) }
            Text(stringResource(providerGroupLabel(requireNotNull(group))), style = MaterialTheme.typography.titleMedium)
        }
        if (group == ProviderProvisioningKind.USER_CONFIGURED) {
            OutlinedButton(onClick = { templatePickerOpen = true }, modifier = Modifier.testTag("provider-add")) { Text(stringResource(R.string.provider_add)) }
        }
        if (group == ProviderProvisioningKind.ON_DEVICE_ASSET && providerService.localModels != null) {
            OutlinedButton(onClick = { localModelOpen = true }) { Text(stringResource(R.string.local_model_title)) }
        }
''' + s[end:]
s = s.replace('if (rows.isEmpty())', 'if (group != null && rows.none { it.provisioning == group })')
start = s.index('        rows.sortedBy')
end = s.index('            ProviderRow(', start)
s = s[:start] + '        rows.filter { it.provisioning == group }.forEach { row ->\n' + s[end:]
s = s.replace('                    ProviderRowActions(', '                    ProviderRowActions(\n                        onContext = { contextRow = row },')
start = s.index('            TextButton({ contextRow = row }')
end = s.index('        }\n    }', start)
s = s[:start] + s[end:]
s = s.replace('                templatePickerOpen = false\n', '                templatePickerOpen = false\n                discovery = emptyList()\n                discoveryMessage = null\n',1)
s = s.replace('                                apiKey = "",\n                            ),', '                                apiKey = "",\n                            ),\n                        preservedHeaders = template.defaultHeaders,')
s = s.replace('            onField = { form = it },', '''            onField = { updated ->
                if (updated.fields.endpoint != currentForm.fields.endpoint || updated.fields.apiKey != currentForm.fields.apiKey || updated.fields.headerName != currentForm.fields.headerName || updated.fields.headerValue != currentForm.fields.headerValue) {
                    discovery = emptyList()
                    discoveryMessage = null
                }
                form = updated.copy(error = null, cleartextConfirmed = updated.cleartextConfirmed && updated.fields.endpoint == currentForm.fields.endpoint)
            },
            discovery = discovery,
            discovering = discovering,
            discoveryMessage = discoveryMessage,
            onDiscover = {
                if (!discovering) {
                    val target = currentForm
                    discovering = true
                    scope.launch {
                        try {
                            val result = discoverProviderForm(target, providerService)
                            if (form == target) {
                                discovery = result.models
                                discoveryMessage = result.message
                            }
                        } finally { discovering = false }
                    }
                }
            },''')
p.write_text(s)

p = base / 'ProviderRowActions.kt'
s = p.read_text().replace('    val onUnload:', '    val onContext: () -> Unit = {},\n    val onUnload:')
p.write_text(s)
p = base / 'ProviderRow.kt'
s = p.read_text()
marker = '        // HXA-059: the backend model list'
idx = s.index(marker)
s = s[:idx] + '''        if (row.managedExternally) {
            OutlinedButton(onClick = actions.onManageAccount, modifier = Modifier.fillMaxWidth().testTag("provider-manage-account")) {
                Text(stringResource(R.string.provider_subscription_manage_account))
            }
        }
        OutlinedButton(onClick = actions.onContext, modifier = Modifier.fillMaxWidth().testTag("provider-context-${row.id}")) {
            Text(stringResource(R.string.chat_context_title))
        }
''' + s[idx:]
s = s.replace('        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n            OutlinedButton(', '        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n            OutlinedButton(',1)
s = s.replace('modifier = Modifier.testTag("provider-test")','modifier = Modifier.fillMaxWidth().testTag("provider-test")').replace('modifier = Modifier.testTag("provider-capabilities")','modifier = Modifier.fillMaxWidth().testTag("provider-capabilities")')
start = s.index('            } else if (row.managedExternally) {')
end = s.index('                TextButton(onClick = actions.onUnload)', start)
s = s[:start] + '            } else if (!row.managedExternally) {\n' + s[end:]
p.write_text(s)

translations = {
'provider_key_optional_label': ('API Key（可选）', 'API Key (optional)'),
'provider_advanced_options': ('高级选项', 'Advanced options'),
'provider_discover_models': ('在线检测模型', 'Discover models'),
'provider_discovering_models': ('正在获取模型列表…', 'Fetching models…'),
'provider_discovery_hint': ('勾选需要的模型；目录可用不代表能力已验证。也可以手输模型 ID。', 'Select models to use. A catalog entry does not verify capabilities. You can also enter a model ID.'),
'provider_discovery_failed': ('无法获取模型列表，请检查地址和凭据，或手输模型 ID。', 'Could not fetch models. Check the endpoint and credentials, or enter a model ID.'),
'provider_groups_back': ('返回模型来源', 'Back to model sources'),
}
for folder in ['values', 'values-en', 'values-zh-rCN']:
    p = Path('app/src/main/res') / folder / 'strings.xml'
    s = p.read_text()
    for key, (zh, en) in translations.items():
        s = s.replace('</resources>', f'    <string name="{key}">{en if folder == "values-en" else zh}</string>\n</resources>')
    p.write_text(s)
