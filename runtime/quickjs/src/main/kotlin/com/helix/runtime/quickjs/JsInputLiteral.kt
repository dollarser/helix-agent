package com.helix.runtime.quickjs

import java.util.Locale

/**
 * HXA-052 host-side encoding of a validated JSON document into a JavaScript STRING LITERAL
 * (architecture doc local-code-execution §3.2: the input enters the wrapper as the IIFE
 * argument, never as a global).
 *
 * The literal is what gets spliced into the wrapper's invocation
 * `})(<literal>);`. Correctness here is a security property: escaping `"` and `\` (plus
 * control characters and U+2028/U+2029) makes it IMPOSSIBLE for input content to close
 * the literal and inject wrapper-level code — a payload like
 * `})(); globalThis.__pwned = 1; //` stays inert text inside the string. The wrapper
 * uses no block comments around the injection point, so a stray comment-closing
 * sequence in the payload carries no special meaning.
 *
 * Pure JVM: unit-tested without Android. The input must already be validated as a JSON
 * document ([JsJsonDocument]) by the caller; this encoder does no JSON parsing.
 */
object JsInputLiteral {
    /**
     * Encodes [value] as a double-quoted JavaScript string literal, quotes included.
     *
     * Escape rules (a strict subset of what ES string literals accept, chosen for
     * maximum parser compatibility):
     * - `"` → `\"`, `\` → `\\` (the two characters that can end or corrupt the literal);
     * - `\n` `\r` `\t` `\b` `\f` → the two-character escape forms;
     * - every other U+0000..U+001F control → `\u00XX` (raw controls are forbidden
     *   inside string literals by the JS grammar);
     * - U+2028/U+2029 (line/paragraph separator) → `\u2028`/`\u2029` (legal inside
     *   literals only from ES2019; QuickJS accepts them raw, but escaping removes any
     *   parser-version doubt);
     * - everything else (including surrogate pairs, emitted as the two raw half
     *   characters — valid ES) passes through unescaped.
     *
     * Worst-case expansion is 6 characters per input character (`\uXXXX`), which the
     * wrapper size bound in [JsAbiAssembly.maxWrappedBytes] accounts for.
     */
    fun encode(value: String): String {
        val out = StringBuilder(value.length + 2)
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
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
