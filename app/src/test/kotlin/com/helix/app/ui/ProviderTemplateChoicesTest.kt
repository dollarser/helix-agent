package com.helix.app.ui

import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderTemplateChoicesTest {
    @Test fun preferredTemplatesComeFirstWithoutLosingOrDuplicatingChoices() {
        val choices = providerTemplateChoices()
        assertEquals(listOf("generic-openai", "deepseek", "openai"), choices.take(3).map { it.id })
        assertEquals(
            ProviderTemplateCatalog.all
                .map {
                    it.id
                }.toSet() - setOf("sglang", "vllm", "ollama"),
            choices.map { it.id }.toSet(),
        )
        assertEquals(choices.size, choices.map { it.id }.toSet().size)
    }
}
