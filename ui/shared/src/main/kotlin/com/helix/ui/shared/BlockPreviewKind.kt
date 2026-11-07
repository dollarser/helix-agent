package com.helix.ui.shared

/** Compiled presentation kinds, never a remotely supplied resource or script route. */
enum class BlockPreviewKind(
    internal val title: Int,
    internal val open: Int,
    internal val error: Int,
    internal val timeoutMillis: Long,
) {
    FORMULA(R.string.formula_title, R.string.formula_open, R.string.formula_error, 5000),
    INLINE_FORMULA(R.string.formula_title, R.string.formula_open, R.string.formula_error, 5000),
    MERMAID(R.string.diagram_title, R.string.diagram_open, R.string.diagram_error, 8000),
    SVG(R.string.svg_title, R.string.svg_open, R.string.svg_error, 8000),
    ;

    internal val maxChars get() = if (this == SVG) SvgPreviewPolicy.MAX_CHARS else FormulaPreviewPolicy.MAX_CHARS
    internal val page get() =
        when (this) {
            FORMULA, INLINE_FORMULA -> FormulaPreviewPolicy.PAGE
            MERMAID -> MermaidPreviewPolicy.PAGE
            SVG -> SvgPreviewPolicy.PAGE
        }

    internal fun valid(source: String) =
        if (this == FORMULA || this == INLINE_FORMULA) {
            source.isNotBlank() && source.length <= maxChars && '\u0000' !in source
        } else if (this == MERMAID) {
            MermaidPreviewPolicy.valid(source)
        } else {
            SvgPreviewPolicy.valid(source)
        }

    internal fun asset(url: String) =
        when (this) {
            FORMULA, INLINE_FORMULA -> FormulaPreviewPolicy.asset(url)
            MERMAID -> MermaidPreviewPolicy.asset(url)
            SVG -> SvgPreviewPolicy.asset(url)
        }

    internal fun html(
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
