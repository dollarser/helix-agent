package com.helix.app.application.context

import com.helix.core.model.AgentMode
import com.helix.core.model.PlanArtifact
import com.helix.core.storage.repository.ExpertProfile
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MemoryContextSnapshot

/** The execution identity and admitted capabilities used to collect this request's facts. */
internal data class SystemPromptRequest(
    val sessionId: String,
    val turnId: String?,
    val directory: FileScopePath,
    val mode: AgentMode,
    val toolNames: Set<String>,
    val expert: ExpertProfile? = null,
)

/** Request-local values, never lazy storage/file readers and never cached across model calls. */
internal data class SystemPromptFacts(
    val request: SystemPromptRequest,
    val project: ProjectPromptFacts? = null,
    val memory: MemoryContextSnapshot? = null,
    val projectInstructions: String = "",
    val goal: GoalPromptFacts? = null,
)

internal data class ProjectPromptFacts(
    val id: String,
    val revision: Long,
    val name: String,
    val description: String,
    val instructions: String,
)

internal data class GoalPromptFacts(
    val turnId: String,
    val runId: String,
    val goalId: String,
    val objective: String,
    val criteria: List<String>,
    val plan: PlanArtifact?,
)
