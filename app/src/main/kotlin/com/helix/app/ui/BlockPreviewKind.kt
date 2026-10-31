package com.helix.app.ui

import com.helix.app.R

/** Compiled presentation kinds, never a remotely supplied resource or script route. */
internal enum class BlockPreviewKind(
    val title: Int,
    val open: Int,
    val error: Int,
    val timeoutMillis: Long,
) {
    FORMULA(R.string.formula_title, R.string.formula_open, R.string.formula_error, 5000),
    MERMAID(R.string.diagram_title, R.string.diagram_open, R.string.diagram_error, 8000),
    ;

    val maxChars get() = FormulaPreviewPolicy.MAX_CHARS
    val page get() = if (this == FORMULA) FormulaPreviewPolicy.PAGE else MermaidPreviewPolicy.PAGE

    fun valid(source: String) =
        if (this == FORMULA) {
            source.isNotBlank() && source.length <= maxChars && '\u0000' !in source
        } else {
            MermaidPreviewPolicy.valid(source)
        }

    fun asset(url: String) = if (this == FORMULA) FormulaPreviewPolicy.asset(url) else MermaidPreviewPolicy.asset(url)

    fun html(
        source: String,
        background: Int,
        foreground: Int,
        size: Float,
        nonce: String,
    ) = if (this == FORMULA) {
        FormulaPreviewPolicy.html(source, background, foreground, size, nonce)
    } else {
        MermaidPreviewPolicy.html(source, background, foreground, size, nonce)
    }
}
