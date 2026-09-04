package com.helix.core.policy

import java.time.Instant

/**
 * Lossless codec for a [UserScope] in the high-sensitivity egress rule store (HXA-068).
 *
 * This is deliberately distinct from [UserScope.toScopeRef]: that reference (stored in
 * `capability_grants.userScopeRef`) is canonical and bounded but LOSSY — it drops display-only
 * fields such as a SAF tree's display name and a root session's start time. A stored egress rule
 * must rehydrate to a scope that compares EQUAL to the live request scope, because the Policy
 * Engine's match is the exact `scope == scope` (ADR-0005: any changed field re-gates the call).
 * Losing a field would silently break the match, so this codec round-trips EVERY constructor
 * field of every scope subtype.
 *
 * Wire format: `hsr1<fs><tag><fs><f1>[<fs><f2>...]` where [FIELD_SEP] is U+0001 (SOH) and
 * [LIST_SEP] (U+0002, STX) separates the elements of a list-valued field. The All-files roots are
 * a `List` in [SharedStorageScope] and the Policy Engine compares scopes by `==` (order-sensitive),
 * so their order is preserved verbatim; the automation package `Set`s are sorted for a stable
 * encoding (set equality is order-independent). Timestamps are stored as whole `epochSecond`
 * values; the fixed scope TTLs never carry sub-second precision that a match depends on. [decode]
 * is fail-closed: any malformed, unknown, or control-corrupted input returns null (the rule then
 * never matches — never a false match).
 */
object UserScopeCodec {
    private const val VERSION = "hsr1"
    private const val FIELD_SEP = ""
    private const val LIST_SEP = ""

    /** Encodes [scope] to its canonical lossless storage string. */
    fun encode(scope: UserScope): String =
        when (scope) {
            is WorkspaceScope -> {
                parts("w", scope.workspaceId)
            }

            is DocumentTreeScope -> {
                parts("s", scope.documentTreeUri, scope.displayName)
            }

            is SharedStorageScope -> {
                parts("f", scope.roots.joinToString(LIST_SEP))
            }

            is BrowserTabScope -> {
                parts("b", scope.tabId, scope.navigationGeneration.toString())
            }

            is AutomationSessionScope -> {
                parts(
                    "a",
                    scope.allowedPackages.sorted().joinToString(LIST_SEP),
                    scope.deniedPackages.sorted().joinToString(LIST_SEP),
                    scope.maxActions.toString(),
                    scope.expiresAt.epochSecond.toString(),
                )
            }

            is RootSessionScope -> {
                parts(
                    "r",
                    scope.startedAt.epochSecond.toString(),
                    scope.expiresAt.epochSecond.toString(),
                    scope.highLevelToolsOnly.toString(),
                )
            }
        }

    /**
     * Rehydrates [encoded] back into a [UserScope]. Returns null (fail closed) on any malformed,
     * unknown-tag, or control-corrupted input — a rule that cannot be decoded must never match.
     */
    fun decode(encoded: String): UserScope? {
        val parts = encoded.split(FIELD_SEP)
        if (parts.size < 2 || parts[0] != VERSION) return null
        return safeDecode(parts[1], parts.drop(2))
    }

    /**
     * Tag dispatch with the single fail-closed catch: every branch delegates to its per-tag decoder,
     * and any reconstructed scope that fails its own constructor invariants (e.g. a control-corrupted
     * field) throws and collapses to null — malformed storage must never match a rule.
     */
    @Suppress("SwallowedException") // malformed storage fails closed to null; the repo surfaces the hard error
    private fun safeDecode(tag: String, fields: List<String>): UserScope? =
        try {
            when (tag) {
                "w" -> exactly(fields, 1)?.let { WorkspaceScope(it[0]) }
                "s" -> exactly(fields, 2)?.let { DocumentTreeScope(it[0], it[1]) }
                "f" -> decodeAllfiles(fields)
                "b" -> decodeBrowserTab(fields)
                "a" -> decodeAutomation(fields)
                "r" -> decodeRoot(fields)
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun decodeBrowserTab(fields: List<String>): UserScope? {
        val f = exactly(fields, 2) ?: return null
        return f[1].toIntOrNull()?.let { BrowserTabScope(f[0], it) }
    }

    private fun parts(
        tag: String,
        vararg fields: String,
    ): String = (listOf(VERSION, tag) + fields.toList()).joinToString(FIELD_SEP)

    private fun exactly(
        fields: List<String>,
        size: Int,
    ): List<String>? = if (fields.size == size) fields else null

    private fun decodeAllfiles(fields: List<String>): UserScope? {
        val joined = exactly(fields, 1) ?: return null
        val roots = joined[0].split(LIST_SEP)
        return if (roots.all { it.isNotEmpty() }) SharedStorageScope(roots) else null
    }

    @Suppress("ReturnCount") // one fail-closed null per undecodable field
    private fun decodeAutomation(fields: List<String>): UserScope? {
        val max = fields[2].toIntOrNull() ?: return null
        val expires = fields[3].toLongOrNull() ?: return null
        return AutomationSessionScope(
            csv(fields[0]),
            csv(fields[1]),
            max,
            Instant.ofEpochSecond(expires),
        )
    }

    @Suppress("ReturnCount") // one fail-closed null per undecodable field
    private fun decodeRoot(fields: List<String>): UserScope? {
        val start = fields[0].toLongOrNull() ?: return null
        val end = fields[1].toLongOrNull() ?: return null
        val high = fields[2].toBooleanStrictOrNull() ?: return null
        return RootSessionScope(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end), high)
    }

    private fun csv(joined: String): Set<String> = if (joined.isEmpty()) emptySet() else joined.split(LIST_SEP).toSet()
}
