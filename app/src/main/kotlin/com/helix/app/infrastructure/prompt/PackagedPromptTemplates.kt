package com.helix.app.infrastructure.prompt

import com.helix.app.application.context.PromptTemplateSource

/** Missing harness instructions are a build defect, not a reason to silently shorten the prompt. */
internal val packagedPromptTemplates: PromptTemplateSource =
    PromptTemplateSource { name ->
        checkNotNull(PromptTemplateSource::class.java.getResourceAsStream("/prompts/$name.md")) {
            "Missing packaged prompt: $name"
        }.bufferedReader().use { it.readText() }
    }
