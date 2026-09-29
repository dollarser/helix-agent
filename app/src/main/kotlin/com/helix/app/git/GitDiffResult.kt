package com.helix.app.git

sealed interface GitDiffResult {
    data class Text(
        val content: String,
    ) : GitDiffResult

    data object Empty : GitDiffResult

    data object Error : GitDiffResult
}
