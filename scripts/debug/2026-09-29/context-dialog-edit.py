from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/ui/ProviderContextDialog.kt')
s=p.read_text().replace('"SwallowedException")','"SwallowedException", "TooGenericExceptionCaught")')
s=s.replace('var loading by remember { mutableStateOf(true) }','var loading by remember(row.id, model) { mutableStateOf(true) }\n    var loaded by remember(row.id, model) { mutableStateOf(false) }\n    var saving by remember { mutableStateOf(false) }\n    var saveFailed by remember { mutableStateOf(false) }\n    var retry by remember { mutableStateOf(0) }')
s=s.replace('LaunchedEffect(row.id, model) {','LaunchedEffect(row.id, model, retry) {')
a=s.index('        val stored = service.contextSettings')
b=s.index('        } finally {',a)
s=s[:a]+'''        try {
            val stored = service.contextSettings(row.id, model)
            settings = stored
            automaticWindow = stored.manualWindow == null
            window = (stored.manualWindow ?: stored.window).toString()
            ratio = stored.triggerPercent.toString()
            loaded = true
            settings = service.discoverContextWindow(row.id, model)
            if (automaticWindow) window = settings.window.toString()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // Keep server bodies and credentials out of presentation errors.
            discoveryFailed = true
'''+s[b:]
s=s.replace('TextButton({ modelMenu = true }, Modifier.testTag("provider-context-model"))', 'TextButton({ modelMenu = true }, Modifier.testTag("provider-context-model"), enabled = !saving)')
s=s.replace('enabled = !loading','enabled = !loading && loaded && !saving')
s=s.replace('enabled = !automaticWindow && !loading','enabled = !automaticWindow && !loading && loaded && !saving')
s=s.replace('if (discoveryFailed) Text(stringResource(R.string.conn_error_local_runtime))','''if (discoveryFailed) {
                    Text(stringResource(R.string.provider_context_load_failed))
                    TextButton({ retry++ }, enabled = !loading && !saving) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
                if (saveFailed) Text(stringResource(R.string.provider_save_failed))''')
a=s.index('                    scope.launch {',s.index('confirmButton'))
b=s.index('\n                },\n                enabled',a)
s=s[:a]+'''                    if (saving) return@TextButton
                    val targetModel = model
                    val targetSettings = settings.copy(
                        manualWindow = if (automaticWindow) null else parsedWindow,
                        triggerPercent = requireNotNull(parsedRatio),
                    )
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            service.saveContextSettings(row.id, targetModel, targetSettings)
                            onDismiss()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }'''+s[b:]
s=s.replace('!loading && parsedRatio', '!loading && loaded && !saving && parsedRatio')
s=s.replace('Text(stringResource(R.string.context_save))','Text(stringResource(if (saving) R.string.provider_save_saving else R.string.context_save))')
p.write_text(s)
for folder,text in [('values','无法检测上下文窗口。已读取的设置仍可编辑，或重试检测。'),('values-zh-rCN','无法检测上下文窗口。已读取的设置仍可编辑，或重试检测。'),('values-en','Could not load context settings or detect the window. Retry, or edit settings that loaded successfully.')]:
 p=Path('app/src/main/res')/folder/'strings.xml'
 p.write_text(p.read_text().replace('</resources>',f'    <string name="provider_context_load_failed">{text}</string>\n</resources>'))
p=Path('app/src/main/kotlin/com/helix/app/ui/ProviderContextDialog.kt')
s=p.read_text()
a=s.index('@Composable')
b=s.index('    var model by',a)
s=s[:a]+'''@Composable
internal fun ProviderContextDialog(
    row: ProviderRowUi,
    service: ProviderService,
    onDismiss: () -> Unit,
) = ProviderContextEditor(
    row,
    load = { service.contextSettings(row.id, it) },
    discover = { service.discoverContextWindow(row.id, it) },
    save = { model, settings -> service.saveContextSettings(row.id, model, settings) },
    onDismiss = onDismiss,
)

@Composable
// Failed storage/discovery stays repairable, using closed UI errors.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "SwallowedException", "TooGenericExceptionCaught")
internal fun ProviderContextEditor(
    row: ProviderRowUi,
    load: suspend (String) -> ProviderContextSettings,
    discover: suspend (String) -> ProviderContextSettings,
    save: suspend (String, ProviderContextSettings) -> Unit,
    onDismiss: () -> Unit,
) {
'''+s[b:]
s=s.replace('var saveFailed by remember {','var saveFailed by remember(row.id, model) {')
s=s.replace('''            val stored = service.contextSettings(row.id, model)
            settings = stored
            automaticWindow = stored.manualWindow == null
            window = (stored.manualWindow ?: stored.window).toString()
            ratio = stored.triggerPercent.toString()
            loaded = true
            settings = service.discoverContextWindow(row.id, model)''','''            if (!loaded) {
                val stored = load(model)
                settings = stored
                automaticWindow = stored.manualWindow == null
                window = (stored.manualWindow ?: stored.window).toString()
                ratio = stored.triggerPercent.toString()
                loaded = true
            }
            settings = settings.copy(serverWindow = discover(model).serverWindow)''')
s=s.replace('service.saveContextSettings(row.id, targetModel, targetSettings)', 'save(targetModel, targetSettings)')
s=s.replace('TextButton({ retry++ }, enabled = !loading && !saving)', 'TextButton({ retry++ }, modifier = Modifier.testTag("provider-context-retry"), enabled = !loading && !saving)')
s=s.replace('Text(stringResource(R.string.provider_context_load_failed))','Text(stringResource(R.string.provider_context_load_failed), Modifier.testTag("provider-context-load-error"))')
s=s.replace('if (saveFailed) Text(stringResource(R.string.provider_save_failed))','if (saveFailed) Text(stringResource(R.string.provider_save_failed), Modifier.testTag("provider-context-save-error"))')
p.write_text(s)
