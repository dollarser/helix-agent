"""Apply the user-requested connection/capability UI split without device operations."""
from pathlib import Path

def edit(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert old in s, path
    p.write_text(s.replace(old, new, 1))

base = 'app/src/main/kotlin/com/helix/app/'
p = base + 'provider/ProviderConnectionProbe.kt'
edit(p, 'import com.helix.core.model.Clock', '''import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.CapabilitySource
import kotlinx.coroutines.flow.toList
import com.helix.core.model.Clock''')
edit(p, 'suspend fun run(providerId: String): ProbeOutcome', 'suspend fun run(providerId: String, detectCapabilities: Boolean = false): ProbeOutcome')
edit(p, 'val outcome = managed.probe(config, provider) ?: probe.probe(provider)', '''val outcome = if (detectCapabilities) {
            managed.probe(config, provider) ?: probe.probe(provider)
        } else {
            checkConnection(config, provider)
        }''')
edit(p, 'is ProbeOutcome.Failed -> {\n                testStatus.modelMetadata', '''is ProbeOutcome.Failed -> {
                // Capability failures do not revoke a separately verified connection.
                if (detectCapabilities) return outcome
                testStatus.modelMetadata''')
edit(p, '\n        return outcome\n    }\n}', '''
        return outcome
    }

    private suspend fun checkConnection(config: ProviderConfig, provider: ModelProvider): ProbeOutcome {
        val catalog = provider.listModels()
        if (catalog is ModelCatalogResult.Failed) {
            return ProbeOutcome.Failed(2, catalog.code, catalog.detail, catalog.retryable)
        }
        // Exactly one ordinary, short generation. No tools, images or explicit effort.
        val events = provider.stream(ModelRequest(
            model = config.model,
            messages = listOf(ModelMessage(ModelRole.USER, "Reply only OK.")),
            maxOutputTokens = 16,
        )).toList()
        val error = events.filterIsInstance<ModelEvent.Error>().firstOrNull()
        if (error != null) return ProbeOutcome.Failed(3, error.code, "connection reply failed", error.retryable)
        if (events.lastOrNull() !is ModelEvent.Completed ||
            events.filterIsInstance<ModelEvent.TextDelta>().none { it.text.isNotBlank() }) {
            return ProbeOutcome.Failed(3, ModelErrorCode.PROTOCOL, "connection reply incomplete", false)
        }
        val previous = (testStatus.statusFor(config.id) as? ConnectionTestStatus.Passed)?.capabilities
        return ProbeOutcome.Ok(
            previous ?: ProviderCapabilities(false, false, false, false, false, false, null, CapabilitySource.CONNECTION_ONLY),
            (catalog as? ModelCatalogResult.Listed)?.models,
        )
    }
}''')
edit('provider/api/src/main/kotlin/com/helix/provider/api/ProviderCapabilities.kt', 'public enum class CapabilitySource {', '''public enum class CapabilitySource {
    /** Only an ordinary text request passed; capability probes have not run. */
    CONNECTION_ONLY,
''')
edit(base+'provider/ProviderUiModels.kt', 'buildList {\n                    add(CapabilityChip', '''buildList {
                    if (caps.source == CapabilitySource.CONNECTION_ONLY) {
                        add(CapabilityChip(R.string.provider_capabilities_unverified))
                        return@buildList
                    }
                    add(CapabilityChip''')
p = base+'provider/ProviderService.kt'
start = Path(p).read_text().index('    /**\n     * Runs the five-phase connection test')
end = Path(p).read_text().index('    suspend fun runConnectionTest', start)
s = Path(p).read_text()
Path(p).write_text(s[:start]+'    /** Catalog discovery plus one short text reply; independent of optional capability detection. */\n'+s[end:])
edit(p, '    /**\n     * The typed config of a persisted provider', '''    /** Explicit capability detection; failures never invalidate a passed connection. */
    suspend fun runCapabilityTest(providerId: String): ProbeOutcome =
        withContext(workScope.coroutineContext) {
            connectionProbe.run(providerId, detectCapabilities = true).also { refreshNow() }
        }

    /**
     * The typed config of a persisted provider''')
edit(base+'ui/ProviderRowActions.kt', '    val onManageAccount: () -> Unit,', '    val onManageAccount: () -> Unit,\n    val onDetectCapabilities: () -> Unit = {},')
p = base+'ui/ProviderScreen.kt'
edit(p, '    var saving by remember', '''    var detectingId by remember { mutableStateOf<String?>(null) }
    var capabilityResults by remember { mutableStateOf<Map<String, com.helix.provider.api.ProbeOutcome>>(emptyMap()) }
    var saving by remember''')
edit(p, '                        onEdit = { modelOverride ->', '''                        onDetectCapabilities = {
                            if (testingId == null) {
                                testingId = row.id
                                detectingId = row.id
                                capabilityResults = capabilityResults - row.id
                                scope.launch {
                                    try {
                                        capabilityResults = capabilityResults + (row.id to providerService.runCapabilityTest(row.id))
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "capability detection failed: ${e.javaClass.simpleName}")
                                        capabilityResults = capabilityResults + (row.id to com.helix.provider.api.ProbeOutcome.Failed(
                                            0, com.helix.core.model.ModelErrorCode.PROTOCOL, "capability detection did not complete", false,
                                        ))
                                    } finally {
                                        testingId = null
                                        detectingId = null
                                    }
                                }
                            }
                        },
                        onEdit = { modelOverride ->''')
edit(p, '                accountUnavailable = accountFailureId == row.id,', '''                accountUnavailable = accountFailureId == row.id,
                detectingCapabilities = detectingId == row.id,
                capabilityOutcome = capabilityResults[row.id],''')
p = base+'ui/ProviderRow.kt'
edit(p, '    accountUnavailable: Boolean,', '''    accountUnavailable: Boolean,
    detectingCapabilities: Boolean = false,
    capabilityOutcome: com.helix.provider.api.ProbeOutcome? = null,''')
edit(p, '        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {', '''        capabilityOutcome?.let { outcome ->
            Text(
                when (outcome) {
                    is com.helix.provider.api.ProbeOutcome.Ok -> stringResource(R.string.provider_capabilities_passed)
                    is com.helix.provider.api.ProbeOutcome.Failed -> stringResource(
                        R.string.provider_capabilities_failed,
                        stringResource(ConnectionTestMapping.codeLabel(outcome.code)),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("provider-capability-result"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {''')
edit(p, 'if (testing) R.string.provider_testing else R.string.provider_connection_test,', 'if (testing && !detectingCapabilities) R.string.provider_testing else R.string.provider_connection_test,')
edit(p, '            if (!row.managedExternally) {\n                TextButton', '''            OutlinedButton(
                onClick = actions.onDetectCapabilities,
                enabled = !testing && row.chatSelectable,
                modifier = Modifier.testTag("provider-capabilities"),
            ) {
                Text(stringResource(if (detectingCapabilities) R.string.provider_capabilities_testing else R.string.provider_capabilities_test))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!row.managedExternally) {
                TextButton''')
strings = {
    'provider_capabilities_test': ('Detect capabilities', '能力检测'),
    'provider_capabilities_testing': ('Detecting capabilities…', '能力检测中…'),
    'provider_capabilities_unverified': ('Capabilities not yet tested', '能力尚未检测'),
    'provider_capabilities_passed': ('Capability checks completed; see verified results above.', '能力检测已完成，已验证项目见上方。'),
    'provider_capabilities_failed': ('Capability detection failed: %1$s. Connection status is unchanged.', '能力检测未通过：%1$s。连接状态保持不变。'),
}
for directory in ['values', 'values-en', 'values-zh-rCN']:
    p = Path('app/src/main/res')/directory/'strings.xml'
    s = p.read_text()
    added = ''.join(f'    <string name="{k}">{v[1 if directory == "values-zh-rCN" else 0]}</string>\n' for k,v in strings.items())
    p.write_text(s.replace('</resources>', added+'</resources>'))
