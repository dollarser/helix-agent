package com.helix.runtime.quickjs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * HXA-052 strict JSON document validation (architecture doc local-code-execution
 * §3.2/§4: first version is JSON in / JSON out only).
 *
 * Used on BOTH ends of the protocol, fail-closed:
 * - the client pre-validates the caller's input document BEFORE binding (invalid JSON
 *   → REQUEST_REJECTED, no isolated process spawned);
 * - the service re-validates the materialized payload (defense in depth for direct
 *   binder users that bypass the client);
 * - the client validates the service's output bytes after a SUCCESS reply (the wrapper
 *   guarantees a JSON document; anything else is a corrupted/lying transport → stable
 *   failure, never a raw-text or base64 fallback).
 *
 * The validator is STRICT per RFC 8259: exact grammar, no trailing content, no leading
 * zeros, no NaN/Infinity, no unescaped controls in strings, `\uXXXX` requires four hex
 * digits. It never returns partial success and never throws on bad input.
 *
 * Nesting depth is bounded by [MAX_DEPTH]: documents deeper than that are rejected as
 * invalid. The bound protects the host validator's own call stack, keeps pathologically
 * deep (and useless-to-JS) documents out of the engine, and still covers any real
 * document (the instrumented suite pins depth-300 round trips; the §10 "deep JSON"
 * attack scenario is handled by rejection, which is the stable, bounded behavior).
 *
 * Pure JVM: unit-tested without Android.
 */
object JsJsonDocument {
    /** Nesting depth (containers) above which a document is rejected as invalid. */
    const val MAX_DEPTH: Int = 512

    /** True iff [jsonUtf8] is exactly one valid UTF-8 encoded JSON document. */
    fun isValidJson(jsonUtf8: ByteArray): Boolean {
        val text = decodeUtf8Strict(jsonUtf8) ?: return false
        return Parser(text).parseDocument()
    }

    /**
     * Strict UTF-8 decode: null on malformed or unmappable bytes. Kept here so every
     * JSON boundary (and the source-UTF-8 service check) shares one decoder policy.
     */
    fun decodeUtf8Strict(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (expected: CharacterCodingException) {
            // REPORT policy: any malformed/unmappable byte run surfaces as this
            // exception — its content is deliberately not retained (the caller
            // rejects the boundary with its own error text).
            null
        }

    /** Recursive-descent structural validator; depth is hard-capped at [MAX_DEPTH]. */
    private class Parser(
        private val s: String,
    ) {
        private var i = 0

        fun parseDocument(): Boolean {
            skipWs()
            if (!parseValue(1)) return false
            skipWs()
            return i == s.length
        }

        @Suppress("ReturnCount") // one return per distinct value grammar
        private fun parseValue(depth: Int): Boolean {
            if (i >= s.length) return false
            return when (val c = s[i]) {
                '{' -> {
                    if (depth > MAX_DEPTH) return false
                    parseContainer(depth, isKey = true, close = '}')
                }

                '[' -> {
                    if (depth > MAX_DEPTH) return false
                    parseContainer(depth, isKey = false, close = ']')
                }

                '"' -> {
                    parseString()
                }

                in "tfn" -> {
                    val literal =
                        when (c) {
                            't' -> "true"
                            'f' -> "false"
                            else -> "null"
                        }
                    if (s.startsWith(literal, i)) {
                        i += literal.length
                        true
                    } else {
                        false
                    }
                }

                in '0'..'9', '-' -> {
                    parseNumber()
                }

                else -> {
                    false
                }
            }
        }

        /**
         * Container entry, called with `i` on the opening `{`/`[` (already matched by
         * the caller): consumes it, handles the empty-container shortcut, then runs
         * the shared entry loop.
         */
        private fun parseContainer(
            depth: Int,
            isKey: Boolean,
            close: Char,
        ): Boolean {
            i++ // '{' or '['
            skipWs()
            if (i < s.length && s[i] == close) {
                i++
                return true
            }
            return parseEntries(depth, isKey, close)
        }

        /**
         * Shared container loop, called after the opening `{`/`[` was consumed and the
         * empty-container check done: parses comma-separated entries until [close]. An
         * entry is an optional `"key":` prefix (objects only, [isKey]) followed by a
         * value. Returns true with `i` past [close]; false on any grammar violation
         * (including a trailing comma before [close]).
         */
        @Suppress("ReturnCount") // one return per distinct grammar failure
        private fun parseEntries(depth: Int, isKey: Boolean, close: Char): Boolean {
            while (true) {
                skipWs()
                if (isKey && !parseKey()) return false
                if (!parseValue(depth + 1)) return false
                skipWs()
                if (i >= s.length) return false
                when (s[i]) {
                    ',' -> {
                        i++
                        // Trailing comma is not JSON.
                        if (i < s.length && s[i] == close) return false
                    }

                    close -> {
                        i++
                        return true
                    }

                    else -> {
                        return false
                    }
                }
            }
        }

        /** Object entry prefix: `"key"` + `:` with optional whitespace around it. */
        @Suppress("ReturnCount") // one return per distinct key-prefix failure
        private fun parseKey(): Boolean {
            if (i >= s.length || s[i] != '"') return false
            if (!parseString()) return false
            skipWs()
            if (i >= s.length || s[i] != ':') return false
            i++
            skipWs()
            return true
        }

        @Suppress("ReturnCount") // one return per distinct string failure
        private fun parseString(): Boolean {
            i++ // '"'
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    i++
                    return true
                }
                if (c == '\\') {
                    val end = escapeEnd(s, i)
                    if (end < 0) return false
                    i = end
                } else if (c in '\u0000'..'\u001F') {
                    // Raw control characters are not allowed in strings.
                    return false
                } else {
                    i++
                }
            }
            return false
        }

        @Suppress("ReturnCount") // one return per distinct grammar failure
        private fun parseNumber(): Boolean {
            if (s[i] == '-') i++
            if (!parseIntegerPart()) return false
            if (i < s.length && s[i] == '.') {
                i++
                if (!parseDigits(min = 1)) return false
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (!parseDigits(min = 1)) return false
            }
            return true
        }

        /**
         * Integer part of a JSON number: `0` or `[1-9][0-9]*` — a leading zero followed
         * by more digits is invalid (the caller's trailing-content check catches it).
         */
        private fun parseIntegerPart(): Boolean {
            if (i < s.length && s[i] == '0') {
                i++
                return true
            }
            return i < s.length && s[i] in '1'..'9' && parseDigits(min = 1)
        }

        /** Consumes the digit run at [i]; requires at least [min] digits. */
        private fun parseDigits(min: Int): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i - start >= min
        }

        private fun skipWs() {
            while (i < s.length && isWs(s[i])) i++
        }
    }

    private fun isWs(c: Char): Boolean =
        when (c) {
            ' ', '\t', '\n', '\r' -> true
            else -> false
        }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** The single-character JSON escapes that follow a backslash (besides `\uXXXX`). */
    private const val SIMPLE_ESCAPES: String = "\"\\/bfnrt"

    /** True iff [s] holds exactly [len] hex digits at [from] (false when out of range). */
    @Suppress("ReturnCount") // one return per distinct check
    private fun isHexRun(s: String, from: Int, len: Int): Boolean {
        if (from + len > s.length) return false
        for (k in 0 until len) {
            if (!isHex(s[from + k])) return false
        }
        return true
    }

    /**
     * Validates the JSON escape whose backslash sits at [start] in [s] and returns the
     * index PAST the escape, or -1 when the escape is malformed (missing character or
     * a `\u` without four hex digits).
     */
    @Suppress("ReturnCount") // one return per malformed form
    private fun escapeEnd(s: String, start: Int): Int {
        val esc = s.getOrNull(start + 1) ?: return -1
        return when {
            esc in SIMPLE_ESCAPES -> {
                start + 2
            }

            esc == 'u' -> {
                if (isHexRun(s, start + 2, 4)) start + 6 else -1
            }

            else -> {
                -1
            }
        }
    }
}
