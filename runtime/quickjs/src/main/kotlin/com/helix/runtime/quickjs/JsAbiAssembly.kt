package com.helix.runtime.quickjs

import java.nio.charset.StandardCharsets

/**
 * HXA-052 assembly of the production JavaScript ABI (architecture doc
 * local-code-execution §3.2, the strict-mode IIFE wrapper).
 *
 * The wrapper is the ONLY production execution path: the HXA-051 raw-evaluate mode
 * (source evaluated verbatim) is gone. Shape (doc §3.2 template, with the HXA-052
 * hardening steps):
 *
 * ```javascript
 * ((__helixInputJson) => {
 *   "use strict";
 *   const stringify = JSON.stringify;      // captured BEFORE user code can override it
 *   const input = JSON.parse(__helixInputJson);
 *
 *   function helixMain() {
 *     // the caller-supplied source, verbatim, as the function body;
 *     // `input` is the const above, captured by closure — user code that
 *     // assigns to it throws the strict-mode read-only TypeError
 *   }
 *
 *   let value;
 *   try {
 *     value = helixMain();
 *   } catch (e) {
 *     if (e === null) throw e;        // engine OOM surface form: on API 29 a bulk
 *                                    // allocation that exhausts the heap can fail
 *                                    // to allocate the Error object itself and the
 *                                    // exception arrives as literal null — rethrow
 *                                    // verbatim so the host-side empty-message OOM
 *                                    // form survives (a user `throw null` is
 *                                    // indistinguishable; accepted, documented)
 *     // non-blank, tamper-proof error text (a thrown STRING, not `new Error`:
 *     // user code that replaces the global Error constructor cannot blank it)
 *     // ...; rethrows the original value when it stringifies to empty
 *   }
 *
 *   const out = stringify(value);
 *   if (out === undefined) return "null";  // JSON has no undefined; undefined maps to null
 *   if (out.length > <maxOutputBytes>) throw "helix output limit exceeded: " + out.length;
 *   return out;                            // the result is ALWAYS a JSON document string
 * })(<host-encoded input literal | null>);
 * ```
 *
 * Security properties (each pinned by the instrumented attack suite):
 * - input is the IIFE argument / a local `const`, never a global — the host encodes it
 *   as an escaped string literal ([JsInputLiteral]), so payload text cannot close the
 *   literal and execute wrapper-level code;
 * - `stringify` is bound BEFORE `helixMain` exists, so user code overriding
 *   `JSON.stringify` (by assignment or by replacing the whole `JSON` global) cannot
 *   alter how the result is encoded or bypass the output bound;
 * - user errors are rethrown as strings with a non-blank [ERROR_PREFIX], eliminating
 *   the HXA-051 raw-mode ambiguity where an empty-message failure (a user
 *   `throw new Error()`) was indistinguishable from the engine OOM form. Two caught
 *   values deliberately keep the RAW (unprefixed) path because they ARE the engine's
 *   no-message OOM surface forms: a caught `null` (pinned on API 29 — a bulk heap
 *   exhaustion can fail to allocate the Error object itself) is rethrown verbatim,
 *   and a value that stringifies to empty (a user `throw ""`) is rethrown verbatim;
 *   both reach the host as the empty-message form and are classified OOM. A user
 *   `throw null` is indistinguishable from the API 29 OOM form and takes the same
 *   OOM path — documented, accepted, and pinned by the attack suite;
 * - the output length check runs AFTER the user-error try/catch, so an output-limit
 *   throw can never be relabelled as a user error, and vice versa.
 *
 * The output threshold is the conservative UTF-16→UTF-8 conversion of
 * `maxOutputBytes`: `out.length` counts UTF-16 code units, and every code unit maps
 * to at least one UTF-8 byte (BMP char: 1-3 bytes per unit; surrogate pair: 4 bytes
 * per 2 units), so `out.length > maxOutputBytes` implies the encoded UTF-8 size is
 * definitely over the limit. The bound is deliberately conservative — an all-CJK
 * result (3 bytes/char) passes this check and is then rejected (or accepted) by the
 * AUTHORITATIVE UTF-8 byte check in [JsExecutionService.deliverResult]; the wrapper
 * check exists to cap the allocation BEFORE the string crosses the Zipline bridge.
 *
 * Pure JVM: the template, the escaping and the size bound are unit-tested without
 * Android or QuickJS.
 */
object JsAbiAssembly {
    /** IIFE parameter that receives the host-encoded input literal. */
    const val INPUT_PARAM_NAME: String = "__helixInputJson"

    /**
     * Prefix of every user-code error surfaced through the wrapper. The prefix is
     * guaranteed non-blank (the wrapper rethrows a string, never a blank), which is
     * what eliminates the raw-mode empty-message OOM ambiguity.
     */
    const val ERROR_PREFIX: String = "helixMain threw: "

    /**
     * Stable marker the service recognizes to reclassify a JS error as
     * [JsExecutionStatus.OUTPUT_LIMIT] (the wrapper's own bound check).
     */
    const val OUTPUT_LIMIT_MARKER: String = "helix output limit exceeded: "

    /**
     * Engine marker for a circular result: `JSON.stringify` of a cyclic structure
     * throws this TypeError at the wrapper's stringify step (outside the user-error
     * try/catch, so it is NOT prefixed). The service reclassifies it as
     * [JsExecutionStatus.OUTPUT_LIMIT], preserving the HXA-051 "result not
     * JSON-encodable" semantics.
     *
     * Device-pinned by HXA-054 against the Zipline 1.27.0 / QuickJS 2021-03-27
     * build (both API 29 and API 36): the engine message is the bare `circular
     * reference` — the V8 phrasing "Converting circular structure to JSON" does
     * NOT exist in the QuickJS binary (verified by binary string inspection and by
     * the instrumented circular-result test). HXA-052's V8-phrased constant was a
     * never-device-verified assumption; this is the pinned fact.
     */
    const val CIRCULAR_RESULT_MARKER: String = "circular reference"

    /**
     * The IIFE argument literal: the unquoted JS literal `null` when there is no
     * input (`JSON.parse(null)` coerces to the string `"null"` and parses to JSON
     * null), otherwise the host-encoded string literal of the validated JSON text.
     * Callers must have validated [inputJsonUtf8] as a JSON document already.
     */
    fun inputLiteral(inputJsonUtf8: ByteArray?): String =
        if (inputJsonUtf8 == null || inputJsonUtf8.isEmpty()) {
            "null"
        } else {
            JsInputLiteral.encode(String(inputJsonUtf8, StandardCharsets.UTF_8))
        }

    /**
     * Assembles the full program: wrapper skeleton + [userSource] (verbatim, as the
     * `helixMain` body) + [inputLiteral] + the numeric output threshold
     * [maxOutputBytes].
     *
     * [userSource] is inserted between its own newline boundaries, so a trailing
     * `// comment` without a newline cannot swallow the closing brace (the body is
     * newline-delimited on both sides). A source whose syntax breaks the assembly
     * (unterminated literal, stray brace, backslash continuation) fails CLOSED as a
     * JS syntax error at parse time — nothing executes.
     */
    fun build(
        userSource: String,
        inputLiteral: String,
        maxOutputBytes: Int,
    ): String =
        "((${INPUT_PARAM_NAME}) => {\n" +
            "  \"use strict\";\n" +
            "  const stringify = JSON.stringify;\n" +
            "  const input = JSON.parse(${INPUT_PARAM_NAME});\n" +
            "\n" +
            "  function helixMain() {\n" +
            userSource +
            "\n  }\n" +
            "\n" +
            "  let value;\n" +
            "  try {\n" +
            "    value = helixMain();\n" +
            "  } catch (e) {\n" +
            "    if (e === null) throw e;\n" +
            "    let m;\n" +
            "    try {\n" +
            "      m = (e !== null && e !== undefined && e.message !== undefined && e.message !== \"\") ? " +
            "String(e.message) : String(e);\n" +
            "    } catch (_) {\n" +
            "      m = \"\";\n" +
            "    }\n" +
            "    if (m === \"\") throw e;\n" +
            "    throw \"helixMain threw: \" + m;\n" +
            "  }\n" +
            "\n" +
            "  const out = stringify(value);\n" +
            "  if (out === undefined) return \"null\";\n" +
            "  if (out.length > $maxOutputBytes) throw \"${OUTPUT_LIMIT_MARKER}\" + out.length;\n" +
            "  return out;\n" +
            "})($inputLiteral);\n"

    /**
     * Bounded total size (UTF-8 bytes) of an assembled program, given the §4.1 limits:
     *
     * - user source ≤ `maxSourceBytes` bytes, and its decoded char count is ≤ its byte
     *   count (every UTF-8 char is ≥ 1 byte);
     * - input literal ≤ 6 chars per input char (worst-case `\uXXXX` escape expansion),
     *   and input chars ≤ input bytes — so ≤ `6 * maxInputBytes` chars;
     * - the wrapper skeleton, threshold digits and quotes are constant
     *   ([WRAPPER_OVERHEAD_BYTES] covers them with a wide margin).
     *
     * The service rejects an assembled program above this bound — a pure
     * defense-in-depth check that the limits keep the whole evaluated program under
     * control even after assembly (the client already rejected the user source above
     * `maxSourceBytes` BEFORE assembly).
     */
    fun maxWrappedBytes(
        maxSourceBytes: Int,
        maxInputBytes: Int,
    ): Int = maxSourceBytes + WRAPPER_OVERHEAD_BYTES + 6 * maxInputBytes
}

private const val WRAPPER_OVERHEAD_BYTES: Int = 8 * 1024
