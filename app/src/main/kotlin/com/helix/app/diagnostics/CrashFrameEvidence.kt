package com.helix.app.diagnostics

/** Code locations and cause types survive restart; exception messages and source paths never do. */
internal object CrashFrameEvidence {
    private val symbol = Regex("[A-Za-z_$<>][A-Za-z0-9_.$<>-]{0,159}")

    fun frames(error: Throwable): List<String> =
        buildList {
            var current: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            repeat(4) {
                val cause = current ?: return@repeat
                if (!seen.add(cause)) return@repeat
                add("cause:${cause.javaClass.name.take(160)}")
                cause.stackTrace.take(8).forEach { frame ->
                    if (symbol.matches(frame.className) && symbol.matches(frame.methodName)) {
                        add("${frame.className}.${frame.methodName}:${frame.lineNumber}")
                    }
                }
                current = cause.cause
            }
        }
}
