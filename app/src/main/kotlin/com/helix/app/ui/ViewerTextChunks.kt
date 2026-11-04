package com.helix.app.ui

/** Bound text layout work per lazy item without splitting a UTF-16 surrogate pair. */
internal fun viewerTextChunks(text: String): List<String> =
    buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 8192, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            add(text.substring(start, end))
            start = end
        }
    }
