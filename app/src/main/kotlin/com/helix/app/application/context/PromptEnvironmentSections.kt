package com.helix.app.application.context

import com.helix.core.agent.PromptOmissionReason
import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.agent.PromptSource
import com.helix.core.model.AgentMode
import com.helix.core.workspace.FileScopePath

/**
 * The harness environment sections EVERY mode assembles (the cross-mode half of HX2-04, the
 * registry-native form of the mainline ChatEnvironmentContext):
 *
 * - [BASE_NAME] always: the harness identity and evidence/authority invariants;
 * - [FILES_NAME] only when the session's admitted tools include the local file tools — the
 *   working directory comes from session facts ([FileScopePath]), never from model-visible text;
 * - [PLAN_NAME] only in Plan mode: plan is read-only and file/tool text cannot enable Act.
 *
 * Harness sections are [PromptSource.BUILTIN_TEMPLATE]; plugin skills remain external content,
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
        source: PromptTemplateSource,
    ) {
        val workingDirectory = directory.toModelReference()
        registry.register(
            PromptSection(BASE_NAME, ORDER_BASE, PromptScope.RUNTIME, PromptSource.BUILTIN_TEMPLATE) {
                source.text("base").replace(PLACEHOLDER, workingDirectory)
            },
        )
        registry.register(
            PromptSection(
                FILES_NAME,
                ORDER_FILES,
                PromptScope.WORKSPACE,
                PromptSource.BUILTIN_TEMPLATE,
                PromptOmissionReason.FILE_TOOLS_UNAVAILABLE,
            ) {
                if (fileToolsAvailable) source.text("files").replace(PLACEHOLDER, workingDirectory) else ""
            },
        )
        registry.register(
            PromptSection(
                PLAN_NAME,
                ORDER_PLAN,
                PromptScope.MODE,
                PromptSource.BUILTIN_TEMPLATE,
                PromptOmissionReason.NOT_PLAN_MODE,
            ) {
                if (mode == AgentMode.PLAN) source.text("plan").replace(PLACEHOLDER, workingDirectory) else ""
            },
        )
    }

    private const val PLACEHOLDER = "{{working_directory}}"
    private const val ORDER_BASE = -2_000
    private const val ORDER_FILES = -1_900
    private const val ORDER_PLAN = -1_800
}
