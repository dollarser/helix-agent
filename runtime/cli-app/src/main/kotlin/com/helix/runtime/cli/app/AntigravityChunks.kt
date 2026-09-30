package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent

/** Preserve surrogate pairs when each delta is independently encoded to UTF-8 for the spool/IPC. */
internal fun antigravityChunks(text: String): Sequence<String> =
    sequence {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + ModelEvent.MAX_DELTA_LENGTH, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            yield(text.substring(start, end))
            start = end
        }
    }
