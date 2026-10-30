package com.helix.app.documents

internal data class PdfTextSlice(
    val text: String,
    val offset: Int,
    val end: Int,
    val total: Int,
) {
    val truncated get() = end < total
}

/** Platform reading order, Unicode code-point offsets, without joining an unbounded page string. */
internal fun pdfTextSlice(
    chunks: Sequence<String>,
    offset: Int,
    limit: Int,
): PdfTextSlice {
    require(offset >= 0 && limit in 1..20_000)
    val result = StringBuilder()
    var total = 0
    val stop = offset.toLong() + limit
    chunks.flatMap { sequenceOf(it, "\n") }.forEach { chunk ->
        val count = chunk.codePointCount(0, chunk.length)
        val next = total.toLong() + count
        if (next > Int.MAX_VALUE) {
            throw com.helix.extensions.documents
                .DocumentException("TEXT_LIMIT")
        }
        val from = maxOf(total, offset)
        val to = minOf(next, stop).toInt()
        if (to > from) {
            result.append(
                chunk.substring(
                    chunk.offsetByCodePoints(0, from - total),
                    chunk.offsetByCodePoints(0, to - total),
                ),
            )
        }
        total = next.toInt()
    }
    if (offset > total) {
        throw com.helix.extensions.documents
            .DocumentException("RANGE_OUT_OF_BOUNDS")
    }
    return PdfTextSlice(result.toString(), offset, minOf(total.toLong(), stop).toInt(), total)
}
