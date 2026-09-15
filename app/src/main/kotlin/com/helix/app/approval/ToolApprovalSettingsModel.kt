package com.helix.app.approval

import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalPreferenceRecord
import com.helix.core.policy.ToolApprovalReason
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * HXA-201 (ADR-0052): the settings screen's read/write model over the standing tool-approval
 * preferences — the ONLY surface the settings UI uses (it never reaches the DAO).
 *
 * Reads: one row per registered tool name (the newest version), where the effective value is
 * parsed by [ToolApprovalPreferenceService] — the SAME resolver the Dispatcher re-reads before
 * each call starts (ADR-0052 point 7), so what the settings screen shows can never drift from
 * what the runtime enforces. The applicable stored records are carried on each row as provenance,
 * so the user sees WHICH scopes a setting comes from. Unset means "follows the default policy" —
 * it is never displayed as if the user had allowed the tool.
 *
 * Writes: [setPreference], [restoreDefault] and [setPreferenceFor] go through
 * [ToolApprovalPreferenceService] (the single write path) in the GLOBAL scope. An ALLOW binds to
 * the row's current contract hash, so a later contract change invalidates it at read time and
 * the row shows the re-confirm state. All three writes are `suspend`: they serialize under one
 * lock (a write is read-modify-write and must not interleave) and run on the IO dispatcher, so
 * the Compose click handlers that call them — on the MAIN thread, where Room's guard would
 * crash — are safe without any caller-side dispatching (HXA-201 device matrix).
 */
class ToolApprovalSettingsModel(
    private val registry: ToolRegistry,
    private val preferences: ToolApprovalPreferenceService,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** One lock for all writes: serialized read-modify-write, no interleaved insert/update races. */
    private val writeMutex = Mutex()

    private suspend fun <T> writeOffMain(block: () -> T): T =
        withContext(Dispatchers.IO) {
            writeMutex.withLock { block() }
        }

    /** One settings row: the newest registered contract of a tool + what is effective right now. */
    data class Row(
        val toolName: String,
        /** The trusted storage key (the origin canonical form), never a display name. */
        val sourceRef: String,
        /** The provider the user searches and sees (built-in / mcp server / a2a agent). */
        val originLabel: String,
        val version: Int,
        val baseRisk: RiskLevel,
        val description: String,
        /** The current descriptor contract an ALLOW set from this row binds to. */
        val contractHash: String,
        val state: ToolApprovalSettingsState,
        /** The applicable stored records, as provenance (value + scope + scopeRef). */
        val records: List<ToolApprovalPreferenceRecord>,
    )

    /**
     * The newest version of every registered tool, as settings rows, optionally filtered by a
     * case-insensitive match on the tool name or the provider label. Rows are keyed per
     * (origin, name): the same tool name registered under two origins (e.g. built-in and an
     * MCP server) is TWO rows — identity is the trusted pair, never the bare name.
     */
    fun rows(query: String = ""): List<Row> {
        val needle = query.trim().lowercase()
        return registry
            .all()
            .groupBy { it.origin.canonicalOf() to it.name.value }
            .mapValues { (_, versions) -> versions.maxBy { it.version.value } }
            .values
            .filter { descriptor ->
                needle.isEmpty() ||
                    descriptor.name.value.contains(needle, ignoreCase = true) ||
                    displayOriginLabel(descriptor.origin).contains(needle, ignoreCase = true)
            }.sortedWith(compareBy({ it.name.value.lowercase() }, { it.origin.canonicalOf() }))
            .map { it.toRow() }
    }

    /**
     * Stores [preference] in the GLOBAL scope for [row] and returns the re-resolved row — the
     * actually-effective result after the write. An ALLOW binds to the row's current contract;
     * ASK and DENY carry no contract.
     */
    suspend fun setPreference(
        row: Row,
        preference: ToolApprovalPreference,
    ): Row = writeOffMain { applyLocked(row, preference) }

    /**
     * Removes the GLOBAL-scope record for [row] — a reset to the unset default, not a fourth
     * state — and returns the re-resolved row. Records in other scopes stay untouched and keep
     * showing through [Row.records].
     */
    suspend fun restoreDefault(row: Row): Row =
        writeOffMain {
            preferences.remove(row.sourceRef, row.toolName, ToolApprovalPreferenceScope.GLOBAL, "")
            rowFor(row)
        }

    /** Assumes [writeMutex] is held and the caller is off the main thread. */
    private fun applyLocked(
        row: Row,
        preference: ToolApprovalPreference,
    ): Row {
        val contractHash = if (preference == ToolApprovalPreference.ALLOW) row.contractHash else null
        preferences.set(
            row.sourceRef,
            row.toolName,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            preference,
            contractHash,
            nowEpochMillis(),
        )
        return rowFor(row)
    }

    private fun rowFor(row: Row): Row =
        rows()
            .firstOrNull { it.sourceRef == row.sourceRef && it.toolName == row.toolName }
            ?: row

    /**
     * The newest registered row for the exact (sourceRef, toolName) identity — null when the
     * tool is no longer registered (e.g. the MCP server disconnected). Same tool names from
     * different origins are distinct tools; the bare name is never enough.
     */
    fun rowForIdentity(
        sourceRef: String,
        toolName: String,
    ): Row? =
        rows()
            .firstOrNull { it.sourceRef == sourceRef && it.toolName == toolName }

    /**
     * Stores [preference] in the GLOBAL scope for the exact (sourceRef, toolName) identity —
     * the path the approval card's "save future preference" actions use — and returns the
     * re-resolved row. Null when the tool is no longer registered (fail-closed: a stale card
     * can never write a standing preference for a vanished tool).
     */
    suspend fun setPreferenceFor(
        sourceRef: String,
        toolName: String,
        preference: ToolApprovalPreference,
    ): Row? =
        writeOffMain {
            val row = rowForIdentity(sourceRef, toolName) ?: return@writeOffMain null
            applyLocked(row, preference)
        }

    private fun ToolDescriptor.toRow(): Row {
        val snapshot = preferences.snapshotFor(origin.canonicalOf(), name.value, contractHash.hex, null, null)
        return Row(
            toolName = name.value,
            sourceRef = origin.canonicalOf(),
            originLabel = displayOriginLabel(origin),
            version = version.value,
            baseRisk = baseRisk,
            description = description,
            contractHash = contractHash.hex,
            state = settingsStateOf(snapshot.effective),
            records = snapshot.records,
        )
    }
}

/**
 * What the settings UI shows for one tool, projected from the runtime's collapsed
 * [EffectiveToolPreference]. The provenance is preserved instead of collapsing to a bare
 * tri-state: an invalidated ALLOW is visibly "re-confirm", not a plain ask and never an allow.
 */
enum class ToolApprovalSettingsState {
    /** Nothing stored live: the call keeps its original policy handling. NOT "the user allowed it". */
    UNSET,

    /** A live, contract-matching user ALLOW: in-scope calls run without asking; high-risk still confirm. */
    ALLOW,

    /** The user explicitly stored ASK. */
    ASK,

    /** A stored ALLOW no longer matches the current contract; it fell back to ASK and needs re-confirm. */
    ASK_INVALIDATED,

    /** The trusted new-tool default (ADR-0052 point 1) resolves to ASK. */
    ASK_NEW_DEFAULT,

    /** The user stored DENY: the tool is hidden from the model and its calls are blocked. */
    DENY,
}

/** The [ToolApprovalSettingsState] projection of a runtime [EffectiveToolPreference]. */
fun settingsStateOf(effective: EffectiveToolPreference): ToolApprovalSettingsState =
    when (effective) {
        is EffectiveToolPreference.Unset -> {
            ToolApprovalSettingsState.UNSET
        }

        is EffectiveToolPreference.Allow -> {
            ToolApprovalSettingsState.ALLOW
        }

        is EffectiveToolPreference.Ask -> {
            when (effective.reason) {
                ToolApprovalReason.ALLOW_INVALIDATED -> ToolApprovalSettingsState.ASK_INVALIDATED
                ToolApprovalReason.NEW_DEFAULT -> ToolApprovalSettingsState.ASK_NEW_DEFAULT
                else -> ToolApprovalSettingsState.ASK
            }
        }

        is EffectiveToolPreference.Deny -> {
            ToolApprovalSettingsState.DENY
        }
    }

/**
 * The provider label the settings UI shows and searches. The STORAGE key is
 * [ToolOrigin.canonicalOf] (it binds hashes the user must not read); the label keeps only the
 * human-meaningful part.
 */
fun displayOriginLabel(origin: ToolOrigin): String =
    when (origin) {
        is ToolOrigin.BuiltInOrigin -> "built-in"
        is ToolOrigin.McpOrigin -> "mcp:${origin.serverId}"
        is ToolOrigin.A2aOrigin -> "a2a:${origin.agentId}"
    }
