package com.helix.runtime.quickjs

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * HXA-052 result encoder: converts the JVM value that Zipline's
 * `QuickJs.evaluate` returns into the canonical JSON bytes of the execution result
 * (doc 03 §3.2/§4.6: results are JSON primitives/objects/arrays only).
 *
 * In production the wrapper's last expression is `JSON.stringify(...)`, so the evaluated
 * value is a `String` — the JSON document text — passed through byte-for-byte (re-quoting
 * would double-encode it). The remaining branches handle the degenerate wrapper escapes
 * where user code returns from the IIFE itself (a top-level object degrades to null, an
 * array to a Zipline-converted List) and still produce a stable JSON result.
 *
 * Pure JVM: unit-tested without Android. Circular containers and non-encodable values
 * fail with [EncodingFailure] — they never silently produce success.
 */
object JsResultJson {
    /** Raised when a value cannot be represented as JSON (doc 03 §4.6 rejects it). */
    class EncodingFailure(
        message: String,
    ) : Exception(message)

    /**
     * Encodes a value returned by `QuickJs.evaluate` as the UTF-8 JSON bytes of the
     * execution result.
     *
     * Contract: a top-level String result IS the JSON text itself (HXA-052's wrapper ends
     * with `return JSON.stringify(...)`, so re-quoting would double-encode); all other
     * top-level values are encoded as JSON, and strings nested inside objects/arrays are
     * encoded as quoted JSON string values.
     */
    fun encode(value: Any?): ByteArray {
        if (value is String) return value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder()
        val openFrames = ArrayDeque<IdentityFrame>()
        encodeValue(value, out, openFrames)
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun encodeValue(
        value: Any?,
        out: StringBuilder,
        openFrames: ArrayDeque<IdentityFrame>,
    ) {
        when (value) {
            null -> out.append("null")

            // Nested strings are ordinary JSON string values (quoted + escaped).
            is String -> appendString(value, out)

            is Boolean -> out.append(value)

            is Int -> out.append(value)

            is Long -> out.append(value)

            is Short -> out.append(value)

            is Byte -> out.append(value)

            is Double -> appendDouble(value, out)

            is Float -> appendDouble(value.toDouble(), out)

            is Map<*, *> -> encodeMap(value, out, openFrames)

            is List<*> -> encodeList(value, out, openFrames)

            is Array<*> -> encodeList(value.asList(), out, openFrames)

            else -> throw EncodingFailure("value of type ${value.javaClass.name} is not JSON-encodable")
        }
    }

    @Suppress("ThrowsCount") // each throw is a distinct fail-closed encoding violation
    private fun encodeMap(
        map: Map<*, *>,
        out: StringBuilder,
        openFrames: ArrayDeque<IdentityFrame>,
    ) {
        if (isCycle(map, openFrames)) throw EncodingFailure("circular result reference (doc 03 §4.6)")
        openFrames.addLast(IdentityFrame(map))
        try {
            out.append('{')
            var first = true
            for ((key, value) in map) {
                if (key == null) throw EncodingFailure("null map key is not JSON-encodable")
                val keyString = key as? String ?: throw EncodingFailure("non-string map key ${key.javaClass.name}")
                if (!first) out.append(',')
                appendString(keyString, out)
                out.append(':')
                encodeValue(value, out, openFrames)
                first = false
            }
            out.append('}')
        } finally {
            openFrames.removeLast()
        }
    }

    private fun encodeList(
        list: List<*>,
        out: StringBuilder,
        openFrames: ArrayDeque<IdentityFrame>,
    ) {
        if (isCycle(list, openFrames)) throw EncodingFailure("circular result reference (doc 03 §4.6)")
        openFrames.addLast(IdentityFrame(list))
        try {
            out.append('[')
            list.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                encodeValue(value, out, openFrames)
            }
            out.append(']')
        } finally {
            openFrames.removeLast()
        }
    }

    private fun isCycle(
        container: Any,
        openFrames: ArrayDeque<IdentityFrame>,
    ): Boolean = openFrames.any { it.contains(container) }

    private fun appendDouble(
        value: Double,
        out: StringBuilder,
    ) {
        when {
            value.isNaN() -> {
                throw EncodingFailure("NaN is not JSON-encodable")
            }

            value.isInfinite() -> {
                throw EncodingFailure("infinity is not JSON-encodable")
            }

            value == value.toLong().toDouble() && value > -(1L shl 52) && value < (1L shl 52) -> {
                out.append(value.toLong())
            }

            else -> {
                out.append(value)
            }
        }
    }

    /** Encodes a JSON string literal (used for object keys; value results pass through). */
    private fun appendString(
        value: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                in '\u0000'..'\u001F' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /**
     * One open container on the encode stack. Identity-based membership only (no
     * equals/hashCode calls into user data), so circular references are detected without
     * trusting user `equals`.
     */
    private class IdentityFrame(
        private val container: Any,
    ) {
        /** True iff [candidate] is the container this frame was opened for (identity). */
        fun contains(candidate: Any): Boolean = container === candidate
    }
}
