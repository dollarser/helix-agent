package com.helix.app.backup

import com.helix.core.workspace.FileScopePath

/** Only importer-owned immutable snapshots keep their location across profile restoration. */
internal object AttachmentRestorePath {
    fun target(reference: String): String? {
        val path = FileScopePath.fromModelReference(reference)
        return if (com.helix.core.workspace.AttachmentSnapshotPath
                .contains(path)
        ) {
            "files/workspaces/app/${path.relativePath}"
        } else {
            null
        }
    }

    fun permitted(target: String): Boolean {
        val prefix = "files/workspaces/app/"
        if (!target.startsWith(prefix)) return false
        return runCatching { target("scope:app:${target.removePrefix(prefix)}") == target }.getOrDefault(false)
    }
}
