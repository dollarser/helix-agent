"""One-time wiring of server-derived per-model metadata; never encode a model roster or effort roster."""
from pathlib import Path
p=Path('runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelCatalog.kt');s=p.read_text().replace('val vision: Boolean,','val vision: Boolean?,').replace('val reasoningEfforts: List<String>,','val reasoningEfforts: List<String>?,')
s=s.replace('require(reasoningEfforts.size <= 10 && reasoningEfforts.all { it in EFFORTS })','require(reasoningEfforts == null || (reasoningEfforts.size <= 16 && reasoningEfforts.all { validEffort(it) }))')
s=s.replace('val EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")','fun validEffort(value: String): Boolean = value.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && value != "off"')
s=s.replace('put("vision", model.vision)','put("vision", model.vision?.let(::JsonPrimitive) ?: JsonNull)').replace('put("reasoning", buildJsonArray { model.reasoningEfforts.forEach { add(JsonPrimitive(it)) } })','put("reasoning", model.reasoningEfforts?.let { levels -> buildJsonArray { levels.forEach { add(JsonPrimitive(it)) } } } ?: JsonNull)')
s=s.replace('row.getValue("vision").jsonPrimitive.boolean,','row.getValue("vision").let { if (it == JsonNull) null else it.jsonPrimitive.boolean },').replace('row.getValue("reasoning").jsonArray.map { it.jsonPrimitive.content }','row.getValue("reasoning").let { if (it == JsonNull) null else it.jsonArray.map { level -> level.jsonPrimitive.content } }')
p.write_text(s)
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexModelCatalog.kt');s=p.read_text().replace('(row["supported_reasoning_levels"] as? JsonArray).orEmpty().mapNotNull','(row["supported_reasoning_levels"] as? JsonArray)?.mapNotNull').replace('value in CliModelInfo.EFFORTS','CliModelInfo.validEffort(value)').replace('}.distinct()','}?.distinct()').replace('(row["input_modalities"] as? JsonArray).orEmpty().map','(row["input_modalities"] as? JsonArray)?.map').replace('"image" in modalities','modalities?.contains("image")');p.write_text(s)
p=Path('app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt');s=p.read_text();at=s.index('    suspend fun modelInfo()')
s=s[:at]+'''    override suspend fun modelMetadata(): Map<String, com.helix.provider.api.ModelMetadata> =
        (loadCatalog() as? com.helix.runtime.cli.client.CliModelCatalog.Listed)?.models.orEmpty().associate { info ->
            info.id to com.helix.provider.api.ModelMetadata(
                info.reasoningEfforts?.map(com.helix.core.model.ReasoningEffort::fromWire), info.vision, info.contextWindow,
            )
        }

'''+s[at:];p.write_text(s)
p=Path('app/src/developer/kotlin/com/helix/app/provider/SubscriptionProviderModule.kt');s=p.read_text().replace('info.reasoningEfforts.any { it != "none" }','info.reasoningEfforts?.any { it != "none" } == true').replace('vision = info.vision,','vision = info.vision == true,');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/provider/ProviderUiModels.kt');s=p.read_text().replace('    val managedExternally: Boolean = false,','    val managedExternally: Boolean = false,\n    val modelMetadata: Map<String, com.helix.provider.api.ModelMetadata> = emptyMap(),');s=s.replace('    val providerId: String? = null,','    val providerId: String? = null,\n    val reasoningEfforts: List<com.helix.core.model.ReasoningEffort> = emptyList(),');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/provider/ProviderService.kt');s=p.read_text().replace('providerRowUi(entity, statusFor(entity.id)).copy(managedExternally = managed.isManaged(entity.id))','''providerRowUi(entity, statusFor(entity.id)).copy(
            managedExternally = managed.isManaged(entity.id),
            modelMetadata = testStatus.modelMetadata.read(entity.id, entity.endpoint),
        )''')
s=s.replace('return contextSettingsStore.read(providerId, config.endpoint.full, model ?: config.model)','''val selectedModel = model ?: config.model
        val stored = contextSettingsStore.read(providerId, config.endpoint.full, selectedModel)
        val detected = metadataFor(providerId, selectedModel)?.contextWindow
        return if (detected == null) stored else stored.copy(serverWindow = detected)''')
at=s.index('    /**\n     * The user-visible manual vision declaration')
s=s[:at]+'''    fun metadataFor(providerId: String, model: String): com.helix.provider.api.ModelMetadata? =
        rows.value.firstOrNull { it.id == providerId }?.modelMetadata?.get(model)

    fun reasoningOptions(providerId: String, model: String): List<com.helix.core.model.ReasoningEffort> {
        val row = rows.value.firstOrNull { it.id == providerId } ?: return emptyList()
        if (!row.chatSelectable) return emptyList()
        val explicit = row.modelMetadata[model]?.reasoningEfforts
        return when {
            explicit != null && explicit.isNotEmpty() -> listOf(com.helix.core.model.ReasoningEffort.OFF) + explicit
            explicit != null -> emptyList()
            model == row.model && row.capabilities?.reasoning == true -> com.helix.core.model.ReasoningEffort.FALLBACK
            else -> emptyList()
        }
    }

'''+s[at:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt');s=p.read_text().replace('(modelId == null || modelId == it.model) && it.capabilities?.reasoning == true,','providerService.reasoningOptions(it.id, modelId ?: it.model).isNotEmpty(),').replace('                it.id,\n            )','                it.id,\n                providerService.reasoningOptions(it.id, modelId ?: it.model),\n            )');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt');s=p.read_text();import re
s,n=re.subn(r'                if \(\(storage.sessions.resolve\(sessionId\).modelId.*?com.helix.core.model.ReasoningEffort.OFF\n                }','''                control.reasoning.takeIf {
                    it in providerService.reasoningOptions(config.id, storage.sessions.resolve(sessionId).modelId ?: config.model)
                } ?: com.helix.core.model.ReasoningEffort.OFF''',s,flags=re.S);assert n==2,n;p.write_text(s)
p=Path('provider/anthropic/src/main/kotlin/com/helix/provider/anthropic/AnthropicRequestEncoder.kt');s=p.read_text().replace('PREFERRED_THINKING_BUDGET[request.reasoning]!!','(PREFERRED_THINKING_BUDGET[request.reasoning] ?: PREFERRED_THINKING_BUDGET.getValue(ReasoningEffort.HIGH))');p.write_text(s)
