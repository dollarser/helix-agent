package com.helix.app.chat

import com.helix.app.goal.registerGoalPromptSections
import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptSnapshot
import com.helix.core.model.AgentMode
import com.helix.core.storage.HelixStorage

/**
 * The single production system-prompt assembly (cross-mode unification, HX2-04): EVERY mode goes
 * through the one [PromptRegistry] — the packaged environment sections for the session's working
 * directory and mode ([PromptEnvironmentSections]), plus the Goal sections when the session's
 * current turn is an active durable goal run ([registerGoalPromptSections]).
 *
 * [build] is invoked on every request build (send, retry, tool-loop back-fill, compaction
 * rebuild) and returns the per-request [PromptSnapshot]: the resolved section list with
 * provenance/trust/content-hash, the exact content that is sent, and the fingerprint the
 * request's records (the `model_calls` row and the `prompt.assembled` audit event) carry — so a
 * request can be traced for which sources and which version of content it used (research doc
 * section 4.4).
 */
internal class SystemPromptContext(
    private val storage: HelixStorage,
    private val workspaceScopeId: String,
    private val projectInstructionsReader: (String) -> String,
    private val templates: PromptTemplateSource = packagedPromptTemplates,
) {
    fun build(
        sessionId: String,
        mode: AgentMode,
        fileToolsAvailable: Boolean,
    ): PromptSnapshot {
        // The working directory the prompt advertises MUST be the one the file tools resolve
        // against: ChatToolCalls binds every relative arg via the same FileToolArguments.directory
        // (which parses a `scope:` directoryRef or falls back to the scope root), so the prompt and
        // the tools agree on default root / selected subdirectory / other authorized root alike.
        val directory =
            FileToolArguments.directory(
                workspaceScopeId,
                storage.sessions
                    .resolve(sessionId)
                    .directoryRef,
            )
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, mode, fileToolsAvailable, templates)
        storage.registerGoalPromptSections(sessionId, { projectInstructionsReader(sessionId) }, registry)
        return registry.resolveAndAssemble()
    }
}
