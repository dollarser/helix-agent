package com.helix.core.agent

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import java.security.MessageDigest

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
 * Where a [PromptSection]'s content comes from (research doc section 4.4). Declaring provenance
 * per section is what makes the assembled prompt traceable: it is the first, structural fact the
 * trust level — and any downstream authority decision — derives from, so a section can never
 * present lower-provenance content as if it were higher-provenance.
 */
enum class PromptSource {
    /** Shipped with / owned by the app: identity, invariants, protocol, tool guidance. */
    BUILTIN_TEMPLATE,

    /** The user's direct request for this step; project files cannot override it. */
    USER_REQUEST,

    /** A project instruction loaded from a selected scope; path/hash/version are recorded. */
    WORKSPACE_INSTRUCTION,

    /** Skill / MCP / A2A / web / file / notification content: a data boundary, not authority. */
    EXTERNAL_CONTENT,

    /** Live capability / workspace state; informational, never a Capability/Policy substitute. */
    DYNAMIC_CAPABILITY,
}

/**
 * How far a section's claims may be trusted (research doc section 4.4), ordered most- to
 * least-authoritative. The level is FIXED by [PromptSource] ([PromptSource.trust]): ordering,
 * scope overrides and loading a project instruction never RAISE it, so a section cannot escalate
 * its own authority. [UNTRUSTED] content is data — its claims are never accepted as Policy or
 * Approval.
 */
enum class TrustLevel {
    SYSTEM,
    USER,
    PROJECT,
    UNTRUSTED,
}

/**
 * The trust level a section's [PromptSource] carries. First-party app content is authoritative
 * within the system; the user's request is authoritative for intent; project instructions sit
 * below both (they cannot override the system boundary or the user's current request); and
 * external content plus live-state info are data only — never an authority.
 */
val PromptSource.trust: TrustLevel
    get() =
        when (this) {
            PromptSource.BUILTIN_TEMPLATE -> TrustLevel.SYSTEM

            PromptSource.USER_REQUEST -> TrustLevel.USER

            PromptSource.WORKSPACE_INSTRUCTION -> TrustLevel.PROJECT

            PromptSource.EXTERNAL_CONTENT,
            PromptSource.DYNAMIC_CAPABILITY,
            -> TrustLevel.UNTRUSTED
        }

/**
 * One registered piece of the system prompt (HX2-04). [name] is the registry key (unique);
 * [order] is the assembly sort key (research doc example: -1000 harness.identity, -900
 * safety.invariants, ... 200 project.instructions); [source] is the content's provenance, from
 * which the section's [trust] is derived; [scope] is auditing metadata; and [provider] supplies
 * the section text at assembly time, returning a blank string to omit the section for this step
 * — the assembly is dynamic per model step, never a fixed string.
 */
data class PromptSection(
    val name: String,
    val order: Int,
    val scope: PromptScope,
    val source: PromptSource,
    val provider: () -> String,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME) {
            "section name must be 1..$MAX_NAME non-blank characters"
        }
    }

    /** Trust is fixed by [source] — nothing in assembly can raise it (research doc section 4.4). */
    val trust: TrustLevel get() = source.trust

    companion object {
        const val MAX_NAME = 64
    }
}

/**
 * A [PromptSection]'s resolved form for one assembly (research doc section 4.4 "consistent
 * snapshot"): the exact content shipped for this step, its provenance, its trust, and a
 * fingerprint of that content. [contentHash] is the SHA-256 of [content] so a request can be
 * audited for which exact bytes were sent and for staleness or tampering of a built-in section.
 */
data class ResolvedPromptSection(
    val name: String,
    val order: Int,
    val scope: PromptScope,
    val source: PromptSource,
    val trust: TrustLevel,
    val content: String,
    val contentHash: String,
)

/**
 * The consistent snapshot of ONE assembled system prompt (research doc section 4.4): the exact
 * non-blank [sections] shipped for the request (with provenance, trust and per-section content
 * hash), the exact [content] that was sent, and a [fingerprint] of that content — the SHA-256 of
 * [content], so a per-request record (a `model_calls` row or an audit event) can say WHICH exact
 * prompt bytes went out, and whether a later request reused or drifted from them, WITHOUT storing
 * the prompt content itself. An assembly with no non-blank sections yields an empty [content]
 * and the fingerprint of the empty string — both deterministic.
 */
data class PromptSnapshot(
    val sections: List<ResolvedPromptSection>,
    val content: String,
    val fingerprint: String,
) {
    /**
     * The system prompt as model messages: one SYSTEM message carrying [content], or none when
     * the assembly was empty (an empty system prompt is sent as nothing, never as a blank
     * message the model would have to parse around).
     */
    fun modelMessages(): List<ModelMessage> =
        if (content.isEmpty()) {
            emptyList()
        } else {
            listOf(ModelMessage(ModelRole.SYSTEM, content))
        }
}

/**
 * Ordered assembly of the system prompt from registered [PromptSection]s (HX2-04). Every mode
 * assembles through this one registry — the packaged environment sections (base / files / plan)
 * for all modes plus the Goal sections on a goal turn — replacing the ad-hoc string
 * concatenation with ordered, scoped, provenance-tagged sections.
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
     * The non-blank sections in output order, each resolved with its provenance, trust and a
     * fingerprint of its exact content — the request's consistent, auditable snapshot (research
     * doc section 4.4). Blank providers are omitted, exactly as [assemble] omits them.
     */
    fun resolve(): List<ResolvedPromptSection> =
        orderedSections().mapNotNull { section ->
            val content = section.provider().trim()
            if (content.isEmpty()) {
                null
            } else {
                ResolvedPromptSection(
                    name = section.name,
                    order = section.order,
                    scope = section.scope,
                    source = section.source,
                    trust = section.trust,
                    content = content,
                    contentHash = sha256Hex(content),
                )
            }
        }

    /**
     * Resolves the prompt once and keeps the whole auditable form: the [PromptSnapshot.sections]
     * (provenance, trust, per-section content hash), the exact [PromptSnapshot.content] that is
     * sent, and the [PromptSnapshot.fingerprint] a per-request record can carry. Production
     * request paths use this — not [assemble] — so the section list and fingerprint of every
     * request persist alongside the request (research doc section 4.4).
     */
    fun resolveAndAssemble(): PromptSnapshot {
        val resolved = resolve()
        val content = resolved.joinToString(separator = "\n\n") { it.content }
        return PromptSnapshot(resolved, content, sha256Hex(content))
    }

    /**
     * Assembles the system prompt: the resolved sections' content joined by a blank line.
     * Returns an empty string when nothing is non-blank.
     */
    fun assemble(): String = resolveAndAssemble().content

    private fun sha256Hex(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
