package com.helix.core.agent

/**
 * The category a [PromptSection] belongs to (HX2-04, research doc section 10). Sections
 * assemble by [PromptSection.order], not by scope; the scope is metadata for auditing and for
 * deciding which sections apply to a given mode / goal / project / skill.
 */
enum class PromptScope {
    IDENTITY,
    SAFETY,
    RUNTIME,
    WORKSPACE,
    PERSONA,
    MODE,
    GOAL,
    TOOL,
    SKILL,
    PROJECT,
}

/**
 * One registered piece of the system prompt (HX2-04). [name] is the registry key (unique);
 * [order] is the assembly sort key (research doc example: -1000 harness.identity, -900
 * safety.invariants, ... 200 project.instructions); [provider] supplies the section text at
 * assembly time and returns a blank string to omit the section for this step — the assembly is
 * dynamic per model step, never a fixed string.
 */
data class PromptSection(
    val name: String,
    val order: Int,
    val scope: PromptScope,
    val provider: () -> String,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME) {
            "section name must be 1..$MAX_NAME non-blank characters"
        }
    }

    companion object {
        const val MAX_NAME = 64
    }
}

/**
 * Ordered assembly of the system prompt from registered [PromptSection]s (HX2-04). Replaces the
 * ad-hoc string concatenation — today the only production system prompt is the ad-hoc Goal
 * report context, and Chat/Plan/Act have none at all — with one registry every mode assembles
 * through.
 *
 * Deterministic: sections assemble in ascending [PromptSection.order]; ties break by [name] so
 * registration order never leaks into the output. A section whose provider returns a blank
 * string is dropped for that step. Build one registry per assembly context and read it; the
 * registry is not synchronized for concurrent mutation.
 */
class PromptRegistry {
    private val sections: LinkedHashMap<String, PromptSection> = LinkedHashMap()

    /** Registers [section]; fails on a duplicate [PromptSection.name]. */
    fun register(section: PromptSection): PromptRegistry {
        require(sections.put(section.name, section) == null) {
            "a section named ${section.name} is already registered"
        }
        return this
    }

    /** The sections in output order (ascending [PromptSection.order], then [name]). */
    fun orderedSections(): List<PromptSection> = sections.values.sortedWith(compareBy({ it.order }, { it.name }))

    /**
     * Assembles the system prompt: ordered sections, blank providers dropped, joined by a blank
     * line. Returns an empty string when nothing is non-blank.
     */
    fun assemble(): String =
        orderedSections()
            .map { it.provider().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n\n")
}
