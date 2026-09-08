package com.helix.tools.browser

import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

// The `browser.*` built-in tools (roadmap HXA-062, doc 09 §3.3): the 11 in-app WebView actions —
// open / navigate / back / forward / reload / snapshot / find / click / type / scroll / screenshot.
//
// Every tool is a thin, fail-closed mapper over the [BrowserToolBridge] port (implemented by
// :feature:browser's `BrowserToolBridgeImpl`, wired in the app). The tool does no WebView work
// itself: it parses its (already schema-validated) arguments, calls exactly one port method, and
// maps the closed outcome to a stable [ToolExecutorResult] — never claiming success on a refusal,
// a stale token, an unknown tab or a timeout. The port (the WebView / token / policy boundary) is
// where the security work lives; this file only shapes the model-facing contract.
//
// Contract highlights (doc 09 §3.3):
// - [BrowserClickTool] / [BrowserTypeTool] may ONLY consume the short-lived node token minted by
//   the most recent [BrowserSnapshotTool]; the port re-validates it against the tab's LIVE state
//   (origin / navigation generation / fingerprint / TTL) and a navigation, refresh, DOM change or
//   TTL expiry makes it stale (a `stale-token` outcome the model reads as "take a fresh snapshot").
// - `browser.type` password / payment / one-time-code fields are refused by default (doc 09 §3.3)
//   — a `refused` outcome carrying the category, never a typed character.
// - [BrowserScreenshotTool] captures only the Helix WebView and saves to the Workspace (doc 09
//   §3.3); the model sees a model-safe Workspace reference plus size/SHA-256, never a raw path.
//
// Output convention: every Completed output is schema-conformant and carries NO JSON nulls — a
// logically-absent value (reason on success, href/name when not applicable) is emitted as `""`
// and an unresolved node index as `-1`.

// ---------------------------------------------------------------------------
// Shared model-facing bounds (mirror the port's own extraction/action bounds, so a port that
// drifts out of them is caught by schema validation rather than silently truncated by the wire).
// ---------------------------------------------------------------------------
internal const val MAX_TAB_ID: Int = 128
internal const val MAX_URL: Int = 2048
internal const val MAX_ORIGIN: Int = 256
internal const val MAX_TOKEN: Int = 512
internal const val MAX_QUERY: Int = 200
internal const val MAX_TYPE_TEXT: Int = 1000
internal const val MAX_ROLE: Int = 32
internal const val MAX_NODE_TEXT: Int = 200
internal const val MAX_NODES: Int = 400
internal const val MAX_SCROLL_PX: Int = 100_000

/** Raw bound on `browser.download`'s optional suggested name (the policy caps it at 128 anyway). */
internal const val MAX_DL_SUGGESTED_NAME: Int = 256

/** Post-sanitize file name is capped by the policy at 128 chars; this is the output bound. */
internal const val MAX_DL_FILE_NAME: Int = 128

/** Bound on the server-declared MIME echoed back in a download result. */
internal const val MAX_DL_MIME: Int = 128

/** Model-visible error detail is bounded well below any port message length. */
internal const val MAX_DETAIL_CHARS: Int = 512

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted
// output, so a one-sided drift is impossible.
internal const val ST_NAV_STARTED: String = "started"
internal const val ST_NAV_DENIED: String = "denied"
internal const val ST_HIST_MOVED: String = "moved"
internal const val ST_HIST_NO_CHANGE: String = "no-change"
internal const val ST_RELOAD_RELOADED: String = "reloaded"
internal const val ST_RELOAD_NO_CHANGE: String = "no-change"
internal const val ST_ACT_PERFORMED: String = "performed"
internal const val ST_ACT_REFUSED: String = "refused"
internal const val ST_ACT_STALE: String = "stale-token"
internal const val ST_SCROLLED: String = "scrolled"
internal const val ST_SAVED: String = "saved"
internal const val ST_DL_SAVED: String = "saved"
internal const val ST_DL_REFUSED: String = "refused"

// ---------------------------------------------------------------------------
// Small schema + argument helpers (module-internal).
// ---------------------------------------------------------------------------

internal fun stringSchema(
    maxLength: Int,
    description: String,
    minLength: Int? = null,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        if (minLength != null) put("minLength", JsonPrimitive(minLength))
        put("maxLength", JsonPrimitive(maxLength))
        put("description", JsonPrimitive(description))
    }

/** A string schema restricted to a fixed set of values (the closed outcome statuses). */
internal fun enumSchema(
    values: List<String>,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", JsonPrimitive(description))
    }

internal fun booleanSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }

internal fun integerSchema(
    description: String,
    minimum: Int?,
    maximum: Int?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("integer"))
        if (minimum != null) put("minimum", JsonPrimitive(minimum))
        if (maximum != null) put("maximum", JsonPrimitive(maximum))
        put("description", JsonPrimitive(description))
    }

internal fun objectSchema(
    properties: JsonObject,
    required: List<String>,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }

/** Reads a present string argument within [maxLength]; schema validation precedes execution. */
internal fun strArg(
    args: JsonObject,
    key: String,
    maxLength: Int,
): String? =
    (args[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.length <= maxLength }

/** Reads an integer argument within [min]..[max] (defensive re-read; see [strArg]). */
internal fun intArg(
    args: JsonObject,
    key: String,
    min: Int,
    max: Int,
): Int? =
    (args[key] as? JsonPrimitive)
        ?.let { p ->
            if (p.isString) p.content.toIntOrNull() else p.intOrNull
        }?.takeIf { it in min..max }

/** Bounds a port error message for the model-visible `detail` (never the audit, which keeps it raw). */
internal fun bounded(detail: String): String {
    val t = detail.trim()
    return if (t.length <= MAX_DETAIL_CHARS) t else t.take(MAX_DETAIL_CHARS) + "…"
}

/** The shared `status` field for browser.click / browser.type (performed / refused / stale-token). */
internal fun actionStatusSchema(): JsonObject =
    enumSchema(
        listOf(ST_ACT_PERFORMED, ST_ACT_REFUSED, ST_ACT_STALE),
        "performed, refused (sensitive field), or stale-token (take a fresh snapshot).",
    )
