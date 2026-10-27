package com.helix.app.diagnostics

import java.util.logging.Logger

/** Internal failure evidence only. Never pass exception messages, arguments or configuration values. */
internal object OperationFailureLog {
    enum class Stage {
        TERMINAL_CONTROL,
        TERMINAL_ENTER,
        TERMINAL_OPEN,
        MOBILE_SCOPE_LOAD,
        MOBILE_SCOPE_SAVE,
        PLUGIN_READINESS,
    }

    fun record(
        stage: Stage,
        failure: Throwable,
    ) {
        Logger.getLogger("HelixOperations").warning(format(stage, failure))
    }

    internal fun format(
        stage: Stage,
        failure: Throwable,
    ): String =
        buildString {
            append(stage.name)
            val seen = mutableSetOf<Throwable>()
            var cause: Throwable? = failure
            repeat(4) {
                val current = cause ?: return@repeat
                if (!seen.add(current)) return@repeat
                append("\n").append(current.javaClass.name)
                current.stackTrace.take(12).forEach { frame ->
                    // Class/method/line identify code; messages and arbitrary source paths do not enter logs.
                    append("\n at ").append(frame.className).append('.').append(frame.methodName)
                    append(':').append(frame.lineNumber)
                }
                cause = current.cause
            }
        }
}
