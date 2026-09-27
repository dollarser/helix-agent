package com.helix.core.storage

import java.nio.file.Path

/** Android NIO creationTime may be mtime; query immutable inode generation or fail closed. */
internal object WorkspaceNativeIdentity {
    init {
        System.loadLibrary("workspace_identity")
    }

    fun witness(path: Path): String = read(path.toString())

    private external fun read(path: String): String
}
