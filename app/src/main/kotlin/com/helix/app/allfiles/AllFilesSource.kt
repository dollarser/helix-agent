package com.helix.app.allfiles

/**
 * A browsable all-files root surfaced to the file manager (HXA-046, developer flavor only): the
 * model-safe scope id (`af-<key>`) plus a user-facing display name. The consumer build reports
 * none — [AllFilesModule.AVAILABLE] is false there and its [AllFilesModule.allFilesSources] is
 * empty. [scopeId] is the only path-shaped value the UI carries; the real root path is never
 * surfaced (doc 10: the model and the UI see the scope id, not the real path).
 */
data class AllFilesSource(
    val scopeId: String,
    val displayName: String,
)
