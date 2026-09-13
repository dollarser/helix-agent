package com.helix.app.git

// The pure, framework-free model of a git working tree's state (P0-B "Git status / diff /
// changed files"). No Android or JGit types, so it unit-tests on the JVM and maps to
// user-visible strings only at the UI boundary (HXA-069).

/** Which side of the index a change is on: staged (index vs HEAD), unstaged (worktree vs index)
 * or an untracked file not yet added. */
enum class GitChangeArea {
    STAGED,
    UNSTAGED,
    UNTRACKED,
}

/** The kind of a single file change. */
enum class GitChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    TYPE_CHANGE,
}

/** One changed file, with the area (staged/unstaged/untracked) and kind the reader derived. */
data class GitChange(
    val path: String,
    val originalPath: String?,
    val kind: GitChangeKind,
    val area: GitChangeArea,
) {
    /** A stable key for the Compose list (a path can appear in both staged and unstaged). */
    val id: String
        get() = "$area-$kind-$path"
}

/** The parsed status of one repository: its branch and the flat list of changed files. */
data class GitStatus(
    val branch: String,
    val changes: List<GitChange>,
) {
    val staged: List<GitChange>
        get() = changes.filter { it.area == GitChangeArea.STAGED }

    val unstaged: List<GitChange>
        get() = changes.filter { it.area == GitChangeArea.UNSTAGED }

    val untracked: List<GitChange>
        get() = changes.filter { it.area == GitChangeArea.UNTRACKED }

    val isClean: Boolean
        get() = changes.isEmpty()
}

/**
 * The outcome of reading a workspace as a git repository. Honest by construction: a non-repo and
 * a read error are distinct states, never folded into a fabricated empty status.
 */
sealed interface GitWorkspaceResult {
    /** No repository was found at or one level below the workspace root. */
    data object NotARepository : GitWorkspaceResult

    /** [status] was read from the repository named [repoName] (its path relative to the
     * workspace root, or the bare directory name when it is the root itself). */
    data class Ready(
        val status: GitStatus,
        val repoName: String,
    ) : GitWorkspaceResult

    /** A read failed after a repository was located; [message] is user-presentable. */
    data class Error(
        val message: String,
    ) : GitWorkspaceResult
}
