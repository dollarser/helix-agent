package com.helix.app.git

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.util.FS
import java.io.File

/** UI comparisons use the actual stored bytes; repository filters never execute here. */
internal class ReadOnlyGitTree : FileTreeIterator {
    constructor(repository: Repository) : super(repository)

    private constructor(parent: ReadOnlyGitTree, directory: File, fs: FS) : super(parent, directory, fs)

    override fun getCleanFilterCommand(): String? = null

    override fun enterSubtree(): AbstractTreeIterator = ReadOnlyGitTree(this, entryFile, fs)
}
