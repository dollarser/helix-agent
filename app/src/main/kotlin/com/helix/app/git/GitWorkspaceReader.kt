package com.helix.app.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads a workspace's git state with JGit (P0-B "Git status / diff / changed files"). JGit is a
 * pure-JVM on-device reader: it needs no PRoot/Ubuntu runtime, so it works identically in the
 * consumer and developer flavors (the doc's native-first principle) and is fully unit-testable.
 *
 * The repository may live at the workspace root or one level below it (the common "clone a repo
 * into the workspace" case); [locate] resolves which. Every read is fail-closed: a non-repo and a
 * read error are distinct, honest states — never a fabricated clean status.
 */
class GitWorkspaceReader(
    private val workspaceRoot: File,
) {
    /**
     * The directory that is a git repository — the workspace root if it is one, otherwise the
     * first (by name) immediate subdirectory that is. `null` when no repository is present.
     */
    fun locate(): File? {
        val subRepos =
            workspaceRoot
                .listFiles()
                ?.filter { it.isDirectory && File(it, ".git").exists() }
                ?.sortedBy { it.name }
                .orEmpty()
        return if (File(workspaceRoot, ".git").exists()) workspaceRoot else subRepos.firstOrNull()
    }

    /** Reads [GitStatus] for the located repository, or an honest [GitWorkspaceResult] state. */
    fun readStatus(): GitWorkspaceResult =
        locate()?.let { repoDir ->
            runCatching { readStatusOf(repoDir) }
                .fold(
                    onSuccess = { GitWorkspaceResult.Ready(it, repoDir.name) },
                    onFailure = { GitWorkspaceResult.Error(it.message ?: "Could not read git status") },
                )
        } ?: GitWorkspaceResult.NotARepository

    /** A unified diff for one changed [path] (working-tree diff, else the staged diff); `""` when
     * there is no textual diff (e.g. an untracked file or no repository). */
    fun diffFor(path: String): String =
        locate()?.let { repoDir -> runCatching { diffOf(repoDir, path) }.getOrDefault("") } ?: ""

    private fun readStatusOf(repoDir: File): GitStatus =
        Git.open(repoDir).use { git ->
            val branch = runCatching { git.repository.branch }.getOrDefault("(none)")
            GitStatus(branch, toChanges(git))
        }

    private fun toChanges(git: Git): List<GitChange> {
        // JGit's worktree-vs-index diff lists untracked files as added, so pull them out of the
        // unstaged set and report them only under untracked (they have no staged/unstaged meaning).
        val untrackedPaths = git.status().call().untracked
        val staged =
            if (hasCommit(git.repository)) {
                git
                    .diff()
                    .setCached(true)
                    .call()
                    .map { entryToChange(it, GitChangeArea.STAGED) }
            } else {
                emptyList()
            }
        val unstaged =
            git
                .diff()
                .call()
                .filter { !untrackedPaths.contains(it.newPath) }
                .map { entryToChange(it, GitChangeArea.UNSTAGED) }
        val untracked =
            untrackedPaths
                .map { p -> GitChange(p, null, GitChangeKind.ADDED, GitChangeArea.UNTRACKED) }
        return (staged + unstaged + untracked).sortedBy { it.path }
    }

    private fun diffOf(
        repoDir: File,
        path: String,
    ): String {
        Git.open(repoDir).use { git ->
            val out = ByteArrayOutputStream()
            val formatter = DiffFormatter(out)
            formatter.setRepository(git.repository)
            formatter.setContext(5)
            val untrackedPaths = git.status().call().untracked
            val unstaged =
                git
                    .diff()
                    .call()
                    .filter { !untrackedPaths.contains(it.newPath) }
                    .filter { it.newPath == path || it.oldPath == path }
            if (unstaged.isNotEmpty()) {
                unstaged
                    .filter { it.newId != null }
                    .forEach { materializeWorktreeBlob(git.repository, File(repoDir, it.newPath)) }
                formatter.format(unstaged)
            } else if (hasCommit(git.repository)) {
                val staged =
                    git
                        .diff()
                        .setCached(true)
                        .call()
                        .filter { it.newPath == path || it.oldPath == path }
                formatter.format(staged)
            }
            formatter.flush()
            return out.toString(Charsets.UTF_8)
        }
    }

    /**
     * The worktree side of an unstaged diff is hashed into the [DiffEntry] but not stored in the
     * object database, so [DiffFormatter] cannot read it. Materialize it as a loose object — a
     * content-addressed, idempotent write, exactly what `git hash-object -w` performs — so the
     * unified diff can render.
     */
    private fun materializeWorktreeBlob(
        repo: Repository,
        worktreeFile: File,
    ) {
        if (worktreeFile.isFile) {
            runCatching {
                repo.newObjectInserter().insert(Constants.OBJ_BLOB, worktreeFile.readBytes())
            }
        }
    }

    /** True when the repository has at least one commit (an unborn `HEAD` resolves to `null`). */
    private fun hasCommit(repo: Repository): Boolean = runCatching { repo.resolve("HEAD") }.getOrNull() != null

    private fun entryToChange(
        entry: DiffEntry,
        area: GitChangeArea,
    ): GitChange {
        val kind =
            when (entry.changeType) {
                DiffEntry.ChangeType.ADD -> GitChangeKind.ADDED
                DiffEntry.ChangeType.MODIFY -> GitChangeKind.MODIFIED
                DiffEntry.ChangeType.DELETE -> GitChangeKind.DELETED
                DiffEntry.ChangeType.RENAME -> GitChangeKind.RENAMED
                DiffEntry.ChangeType.COPY -> GitChangeKind.COPIED
                else -> GitChangeKind.MODIFIED
            }
        val path =
            if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
        val original = if (entry.changeType == DiffEntry.ChangeType.RENAME) entry.oldPath else null
        return GitChange(path, original, kind, area)
    }
}
