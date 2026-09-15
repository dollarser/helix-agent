package com.helix.core.policy

/**
 * The pure "is this tool new in the current build?" decision for the trusted tool-registration/
 * upgrade baseline (HXA-200 Gap 2, ADR-0052 point 1; 2026-09-15 mechanism addendum). Stateless and
 * JVM-testable: it consumes the three versionCode facts the durable baseline persists and returns
 * whether the tool is "new" — the sole input to the [ToolApprovalReason.NEW_DEFAULT] default. It is
 * NEVER inferred from an empty preference record or a model claim (ADR-0052 point 1).
 */
object ToolBaseline {
    /**
     * A tool is NEW in the current build iff it has a baseline marker that was first seen in
     * EXACTLY the current [currentVersionCode] AND that versionCode is strictly after the device's
     * [foundingVersionCode].
     *
     * - `foundingVersionCode == null`: the trusted path has never run (a fresh database) — there is
     *   no baseline, so nothing is new (everything stays UNSET).
     * - `firstSeenVersionCode == null`: this tool was never trustedly registered — no basis to call
     *   it new, so it is not (it stays UNSET).
     * - `firstSeenVersionCode == foundingVersionCode == currentVersionCode`: the tool is part of the
     *   founding baseline (a fresh install) — OLD, not new; a fresh install never forces ASK on its
     *   bundled tools (点1 "既有工具 UNSET").
     * - `firstSeenVersionCode == currentVersionCode > foundingVersionCode`: the tool first appeared
     *   in the current, post-founding build — NEW (the upgrade introduced it). Stable across
     *   restarts of the same build; it ages to OLD once a later build becomes current.
     */
    fun isNewDefault(
        currentVersionCode: Long,
        foundingVersionCode: Long?,
        firstSeenVersionCode: Long?,
    ): Boolean =
        firstSeenVersionCode != null &&
            foundingVersionCode != null &&
            firstSeenVersionCode == currentVersionCode &&
            currentVersionCode > foundingVersionCode
}
