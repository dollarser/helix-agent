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
    INLINE_FORMULA(R.string.formula_title, R.string.formula_open, R.string.formula_error, 5000),
    MERMAID(R.string.diagram_title, R.string.diagram_open, R.string.diagram_error, 8000),
    SVG(R.string.svg_title, R.string.svg_open, R.string.svg_error, 8000),
    ;

    val maxChars get() = if (this == SVG) SvgPreviewPolicy.MAX_CHARS else FormulaPreviewPolicy.MAX_CHARS
    val page get() =
        when (this) {
            FORMULA, INLINE_FORMULA -> FormulaPreviewPolicy.PAGE
            MERMAID -> MermaidPreviewPolicy.PAGE
            SVG -> SvgPreviewPolicy.PAGE
        }

    fun valid(source: String) =
        if (this == FORMULA || this == INLINE_FORMULA) {
            source.isNotBlank() && source.length <= maxChars && '\u0000' !in source
        } else if (this == MERMAID) {
            MermaidPreviewPolicy.valid(source)
        } else {
            SvgPreviewPolicy.valid(source)
        }

    fun asset(url: String) =
        when (this) {
            FORMULA, INLINE_FORMULA -> FormulaPreviewPolicy.asset(url)
            MERMAID -> MermaidPreviewPolicy.asset(url)
            SVG -> SvgPreviewPolicy.asset(url)
        }

    fun html(
        source: String,
        background: Int,
        foreground: Int,
        size: Float,
        nonce: String,
    ) = if (this == FORMULA || this == INLINE_FORMULA) {
        FormulaPreviewPolicy.html(source, background, foreground, size, nonce, inline = this == INLINE_FORMULA)
    } else if (this ==
        MERMAID
    ) {
        MermaidPreviewPolicy.html(source, background, foreground, size, nonce)
    } else {
        SvgPreviewPolicy.html(source, background, foreground, size, nonce)
    }
}
