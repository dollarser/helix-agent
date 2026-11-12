package com.helix.feature.conversation.application.input

import com.helix.core.model.input.InputSkillActivation

/** Model-facing text for an explicit skill-only input; never rewrites the durable user draft. */
fun skillInputText(
    text: String,
    skills: List<InputSkillActivation>,
): String =
    if (text.isBlank() && skills.isNotEmpty()) {
        "Use skill: " + skills.joinToString(", ") { it.name }
    } else {
        text
    }
