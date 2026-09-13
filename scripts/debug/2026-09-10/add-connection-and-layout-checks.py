"""Add account-only request regressions and finish adaptive settings sections."""
from pathlib import Path
p=Path('runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexSmokeRefreshTest.kt');s=p.read_text();pos=s.index('    private class Fixture(');s=s[:pos]+'''    @Test fun accountConnectionRefreshesOnceWithoutGenerating() {
        Fixture().use {
            it.smoke.checkConnection()
            assertEquals(listOf("GET", "GET"), it.requests.map { request -> request.method })
            assertEquals(1, it.refreshCalls)
        }
    }

    @Test fun accountConnectionRejectsUnauthorizedWithoutGenerating() {
        Fixture(alwaysUnauthorized = true).use {
            assertEquals(401, assertThrows(CodexSmokeException::class.java) { it.smoke.checkConnection() }.httpCode)
            assertEquals(listOf("GET", "GET"), it.requests.map { request -> request.method })
        }
    }

'''+s[pos:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/provider/ProviderConnectionCheck.kt');p.write_text(p.read_text().replace('import com.helix.core.storage.repository.ProviderConfigSpec\n',''))
p=Path('app/src/test/kotlin/com/helix/app/provider/ProviderConnectionCheckTest.kt');p.write_text('''package com.helix.app.provider

import com.helix.core.model.*
import com.helix.provider.api.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderConnectionCheckTest {
    private val config = ProviderConfig("fixture", "fixture", ProviderProtocol.OPENAI_RESPONSES,
        NormalizedEndpoint.parse("https://fixture.invalid"), "expensive-unavailable-model", emptyMap(), SecretAlias("fixture"), "{}")

    @Test fun authenticatedCatalogDoesNotGenerateOrDependOnConfiguredModel() = runBlocking {
        val provider = Fixture(ModelCatalogResult.Listed(listOf("different-model")))
        val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Ok
        assertEquals(listOf("different-model"), result.models)
        assertEquals(CapabilitySource.CONNECTION_ONLY, result.capabilities.source)
        assertEquals(0, provider.generations)
        assertEquals(0, provider.fallbackCatalogCalls)
    }

    @Test fun failedAccountDoesNotGenerateOrFallBackToCachedModels() = runBlocking {
        val provider = Fixture(ModelCatalogResult.Failed(ModelErrorCode.AUTH, "rejected", false))
        val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Failed
        assertEquals(ModelErrorCode.AUTH, result.code)
        assertEquals(0, provider.generations)
        assertEquals(0, provider.fallbackCatalogCalls)
    }

    @Test fun localCatalogWithoutAccountVerificationStillRequiresRealConnection() = runBlocking {
        val provider = Fixture(null)
        val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Failed
        assertEquals(ModelErrorCode.TRANSPORT, result.code)
        assertEquals(1, provider.generations)
        assertEquals(1, provider.fallbackCatalogCalls)
    }

    private inner class Fixture(private val account: ModelCatalogResult?) : ModelProvider, SubscriptionConnectionProvider {
        var generations = 0
        var fallbackCatalogCalls = 0
        override val descriptor = ProviderDescriptor(config.id, config.displayName, config.protocol, config.model, config.endpoint)
        override suspend fun connectionCatalog() = account
        override suspend fun listModels(): ModelCatalogResult {
            fallbackCatalogCalls++
            return ModelCatalogResult.Listed(listOf(config.model))
        }
        override suspend fun validateConfiguration(): ProviderCheckResult = error("not used")
        override fun stream(request: ModelRequest) = flowOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.TRANSPORT, true)).also { generations++ }
    }
}
''')
# Explicit imports for project style.
s=p.read_text().replace('import com.helix.core.model.*','\n'.join('import com.helix.core.model.'+n for n in ['ModelErrorCode','ModelEvent','ModelRequest','NormalizedEndpoint','ProviderProtocol','SecretAlias'])).replace('import com.helix.provider.api.*','\n'.join('import com.helix.provider.api.'+n for n in ['CapabilitySource','ModelCatalogResult','ModelProvider','ProbeOutcome','ProviderCheckResult','ProviderConfig','ProviderDescriptor'])).replace('import org.junit.Assert.*','import org.junit.Assert.assertEquals');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/AuditScreen.kt');s=p.read_text().replace('Text(record.startedAt,','Text(remember(record.startedAt) {\n                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)\n                    .format(java.util.Date(record.startedAt))\n            },');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/SettingsActions.kt');s=p.read_text()+'''
@Composable
internal fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
''';s=s.replace('import androidx.compose.foundation.layout.fillMaxWidth','import androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.fillMaxWidth');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt');s=p.read_text()
for old in ['ProotRuntimeSection()','LanguageSection()','ProviderManager(providerService)','RunControlSettingsSection(runControlStore)']:s=s.replace('        '+old,'        SettingsGroup { '+old+' }')
s=s.replace('        RootModule.Section(profile)\n\n        AutomationModule.Section(profile)','        if (profile == SafetyProfile.ADVANCED) {\n            SettingsGroup { RootModule.Section(profile) }\n            SettingsGroup { AutomationModule.Section(profile) }\n        }');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/egress/EgressRuleSection.kt');s=p.read_text().replace('Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {','com.helix.app.ui.SettingsActions {');p.write_text(s)
