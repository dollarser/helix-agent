package com.helix.app.ui

import com.helix.extensions.skills.UserSkillActivation

internal data class SkillUseActions(
    val context: SkillUseContext,
    val invoke: (suspend (UserSkillActivation) -> Boolean)?,
    val dismiss: () -> Unit,
    val completed: () -> Unit = dismiss,
)
