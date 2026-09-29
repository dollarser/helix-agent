package com.helix.app.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import java.io.File

/**
 * Reads a workspace's git state with JGit (P0-B "Git status / diff / changed files"). JGit is a
 * pure-JVM on-device reader: it needs no PRoot/Ubuntu runtime, so it works identically in the
 * consumer and developer flavors (the doc's native-first principle) and is fully unit-testable.
 *
 * The repository may live at the workspace root or one level below it (the common "clone a repo
 * into the workspace" case); [locate] resolves which. The reader is strictly READ-ONLY — it
 * never writes into `.git` (an unstaged diff is rendered in memory by [WorktreeDiffRenderer]) —
 * and every read is fail-closed: a non-repo and a read error are distinct, honest states, never
 * a fabricated clean status.
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
            runCatching {
                openReadOnly(repoDir).use { git ->
                    val branch = runCatching { git.repository.branch }.getOrDefault("(none)")
                    GitStatus(branch, toChanges(git))
                }
            }.fold(
                onSuccess = { GitWorkspaceResult.Ready(it, repoDir.name) },
                onFailure = { GitWorkspaceResult.Error(it.message ?: "Could not read git status") },
            )
        } ?: GitWorkspaceResult.NotARepository

    /** Read exactly the selected area; failures never masquerade as an empty diff. */
    fun readDiff(
        path: String,
        area: GitChangeArea,
    ): GitDiffResult =
        try {
            val text = diffFor(path, area)
            if (text.isEmpty()) GitDiffResult.Empty else GitDiffResult.Text(text)
        } catch (_: java.io.IOException) {
            GitDiffResult.Error
        } catch (_: org.eclipse.jgit.api.errors.GitAPIException) {
            GitDiffResult.Error
        } catch (_: org.eclipse.jgit.api.errors.JGitInternalException) {
            GitDiffResult.Error
        } catch (_: IllegalArgumentException) {
            GitDiffResult.Error
        }

    fun diffFor(
        path: String,
        area: GitChangeArea = GitChangeArea.UNSTAGED,
    ): String = diffOf(requireNotNull(locate()) { "Repository unavailable" }, path, area)

    private fun toChanges(git: Git): List<GitChange> {
        // JGit's worktree-vs-index diff lists untracked files as added, so pull them out of the
        // unstaged set and report them only under untracked (they have no staged/unstaged meaning).
        val untrackedPaths = readUntracked(git)
        val staged = stagedEntries(git).map { entryToChange(it, GitChangeArea.STAGED) }
        val unstaged =
            git
                .diff()
                .setShowNameAndStatusOnly(true)
                .setNewTree(ReadOnlyGitTree(git.repository))
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
        area: GitChangeArea,
    ): String =
        openReadOnly(repoDir).use { git ->
            val entries =
                when (area) {
                    GitChangeArea.STAGED -> {
                        stagedEntries(git)
                    }

                    GitChangeArea.UNSTAGED -> {
                        val untracked = readUntracked(git)
                        git
                            .diff()
                            .setShowNameAndStatusOnly(true)
                            .setNewTree(ReadOnlyGitTree(git.repository))
                            .call()
                            .filterNot { it.newPath in untracked }
                    }

                    GitChangeArea.UNTRACKED -> {
                        emptyList()
                    }
                }
            val renderer = WorktreeDiffRenderer(repoDir)
            entries
                .filter { it.newPath == path || it.oldPath == path }
                .joinToString("") { renderer.render(git.repository, it, area == GitChangeArea.STAGED) }
        }

    private fun stagedEntries(git: Git): List<DiffEntry> {
        val command = git.diff().setShowNameAndStatusOnly(true).setCached(true)
        if (git.repository.resolve("HEAD") == null) command.setOldTree(EmptyTreeIterator())
        return command.call()
    }

    private fun openReadOnly(directory: File): Git = Git.open(directory, ReadOnlyGitFileSystem())

    private fun readUntracked(git: Git): Set<String> =
        git
            .status()
            .setWorkingTreeIt(ReadOnlyGitTree(git.repository))
            .setIgnoreSubmodules(IgnoreSubmoduleMode.ALL)
            .call()
            .untracked

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
