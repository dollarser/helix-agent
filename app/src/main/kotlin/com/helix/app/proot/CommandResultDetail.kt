package com.helix.app.proot

// The command details page (HXA-194) reads ONLY persisted facts: the tool call, its settled
// result row, the prepared-job binding audit row and the locally persisted, integrity-checked
// result archive. Browsing never binds the Runtime, never submits and never acknowledges —
// the explicit reconciliation stays the existing session entry (`查看结果`).

/** The Linux command tools whose calls carry a command result (the developer proot tools). */
val COMMAND_TOOL_NAMES: Set<String> = setOf("bash", "code.linux.run", "code.linux.job.start")

/** The display state of one command result; every value gets its own user-visible text. */
enum class CommandDetailState {
    /** The turn is still in flight: status only — output is viewable AFTER the command ends. */
    RUNNING,

    /** Submission succeeded; a passive page cannot establish whether the process is still running. */
    SUBMITTED,

    /** The job finished with the verified terminal record. */
    SUCCEEDED,

    /** The job ended non-succeeded or the execution failed. */
    FAILED,

    /** Cancelled (before start or while running). */
    CANCELLED,

    /** Refused at the approval gate; never executed. */
    DENIED,

    /** The outcome cannot be established from the persisted facts (or was evicted upstream). */
    UNKNOWN,

    /** The Runtime's evidence for this job expired (30-day retention). */
    EVIDENCE_EXPIRED,

    /** The locally persisted archive failed its integrity check. */
    READ_FAILED,
}

/**
 * The prepared-job binding facts (version-agnostic view over the audit row: the v1 rows
 * written before the session binding existed carry exactly these three fields).
 */
data class CommandJobBindingFacts(
    val jobId: String,
    val executionId: String,
    val inputManifestSha256: String,
)

/**
 * The flavor-seam read of everything proot-specific for one call: the binding (developer
 * only) and the locally persisted archive preview (developer only). `archiveReadFailed`
 * is true when the local archive existed but failed verification — the page then shows the
 * distinct 读取失败 state and no streams at all.
 */
data class CommandBrowseFacts(
    val binding: CommandJobBindingFacts?,
    val archive: ProotRecoveredOutput?,
    val archiveReadFailed: Boolean,
    val detached: DetachedCommandFacts? = null,
)

/** Runtime observation or explicit host disposition, separate from output and budget settlement. */
data class DetachedCommandFacts(
    val state: String,
    val exitCode: Int?,
    val settled: Boolean,
)

/**
 * The persisted facts of one command call as the details page may read them: the call and
 * turn states plus the call's owning session and its settled result row (all nullable —
 * a settled call can lack its result row, and the page must say "unknown", not guess).
 */
data class CommandResultFacts(
    val callState: String,
    val turnState: String,
    val sessionId: String,
    val resultStatus: String?,
    val resultSummary: String?,
    val resultContent: String?,
)

/** One command's detail, projected from persisted facts (never re-executed). */
data class CommandResultView(
    val callId: String,
    val toolName: String,
    /** The command as already visible in the request parameters. */
    val commandText: String,
    val callState: String,
    val turnState: String,
    /** The session the command ran under (the return-source target). */
    val sessionId: String,
    /** The workspace scope the command ran under. */
    val scopeLabel: String,
    val binding: CommandJobBindingFacts?,
    val state: CommandDetailState,
    val exitCode: Int?,
    /** The persisted failure/unknown detail line (model-facing text, shown verbatim). */
    val detail: String?,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val files: List<ProotRecoveredFile>,
    val acknowledged: Boolean?,
    /** Terminal result with no output at all: gets its own display line. */
    val noOutput: Boolean,
    val settlementPending: Boolean = false,
)

/** One command row of the task page's command list (HXA-194 entry from the task row). */
data class CommandEntry(
    val callId: String,
    val toolName: String,
    val commandText: String,
    val state: String,
    val turnState: String,
)
