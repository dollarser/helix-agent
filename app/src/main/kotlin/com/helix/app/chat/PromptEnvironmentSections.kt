package com.helix.app.chat

import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.agent.PromptSource
import com.helix.core.model.AgentMode
import com.helix.core.workspace.FileScopePath

/**
 * Loads one packaged, allowlisted prompt template by name. Only [PromptTemplateSource]s can
 * supply environment section content — workspace files and MCP prompts cannot replace a packaged
 * template (the allowlist is the section registration below; see
 * `app/src/main/resources/prompts/README.md`).
 */
internal fun interface PromptTemplateSource {
    fun text(name: String): String
}

/**
 * The packaged template loader. Fails closed when a packaged template is missing — a missing
 * harness instruction is a build defect, never something to paper over with a shorter prompt.
 */
internal val packagedPromptTemplates: PromptTemplateSource =
    PromptTemplateSource { name ->
        checkNotNull(PromptEnvironmentSections::class.java.getResourceAsStream("/prompts/$name.md")) {
            "Missing packaged prompt: $name"
        }.bufferedReader().use { it.readText() }
    }

/**
 * The harness environment sections EVERY mode assembles (the cross-mode half of HX2-04, the
 * registry-native form of the mainline ChatEnvironmentContext):
 *
 * - [BASE_NAME] always: the harness identity and evidence/authority invariants;
 * - [FILES_NAME] only when the session's admitted tools include the local file tools — the
 *   working directory comes from session facts ([FileScopePath]), never from model-visible text;
 * - [PLAN_NAME] only in Plan mode: plan is read-only and file/tool text cannot enable Act.
 *
 * All three are [PromptSource.BUILTIN_TEMPLATE] (SYSTEM trust): packaged application content,
 * ordered before every Goal section so the harness invariants precede the goal's own text.
 */
internal object PromptEnvironmentSections {
    const val BASE_NAME = "env.base"
    const val FILES_NAME = "env.files"
    const val PLAN_NAME = "env.plan"

    fun register(
        registry: PromptRegistry,
        directory: FileScopePath,
        mode: AgentMode,
        fileToolsAvailable: Boolean,
        source: PromptTemplateSource = packagedPromptTemplates,
    ) {
        val workingDirectory = directory.toModelReference()
        registry.register(
            PromptSection(BASE_NAME, ORDER_BASE, PromptScope.RUNTIME, PromptSource.BUILTIN_TEMPLATE) {
                source.text("base").replace(PLACEHOLDER, workingDirectory)
            },
        )
        if (fileToolsAvailable) {
            registry.register(
                PromptSection(FILES_NAME, ORDER_FILES, PromptScope.WORKSPACE, PromptSource.BUILTIN_TEMPLATE) {
                    source.text("files").replace(PLACEHOLDER, workingDirectory)
                },
            )
        }
        if (mode == AgentMode.PLAN) {
            registry.register(
                PromptSection(PLAN_NAME, ORDER_PLAN, PromptScope.MODE, PromptSource.BUILTIN_TEMPLATE) {
                    source.text("plan").replace(PLACEHOLDER, workingDirectory)
                },
            )
        }
    }

    private const val PLACEHOLDER = "{{working_directory}}"
    private const val ORDER_BASE = -2_000
    private const val ORDER_FILES = -1_900
    private const val ORDER_PLAN = -1_800
}
