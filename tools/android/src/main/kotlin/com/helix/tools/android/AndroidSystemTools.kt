@file:Suppress("TooManyFunctions") // android.*/clipboard.* tools share the internal schema/arg helpers

package com.helix.tools.android

import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Model-facing input bounds (defensive re-reads; the input schema already enforces these).
internal const val MAX_URL: Int = 2048
internal const val MAX_SHARE_TEXT: Int = 10_000
internal const val MAX_SHARE_SUBJECT: Int = 512
internal const val MAX_CLIPBOARD_WRITE: Int = 4_000

// Model-visible error detail is bounded well below any port message length.
internal const val MAX_DETAIL: Int = 512

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted output,
// so a one-sided drift is impossible.
internal const val ST_OPENED: String = "opened"
internal const val ANDROID_SYSTEM_REFUSED: String = "refused"
internal const val ST_NO_HANDLER: String = "no-handler"
internal const val ST_READ: String = "read"
internal const val ST_WRITTEN: String = "written"
internal const val ST_SHARED: String = "shared"

// ---------------------------------------------------------------------------
// Small schema + argument helpers (internal, shared with the notifications/calendar tools),
// mirroring the browser tools' conventions.
// ---------------------------------------------------------------------------
internal fun stringSchema(
    maxLength: Int,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("maxLength", JsonPrimitive(maxLength))
        put("description", JsonPrimitive(description))
    }

internal fun enumSchema(
    values: List<String>,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", JsonPrimitive(description))
    }

internal fun androidSystemToolsBooleanSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }

internal fun integerSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("integer"))
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

/**
 * Reads a string argument that is present, a string, non-blank and within [maxLength]. Arguments are
 * already input-schema-validated before the executor runs; this is a defensive re-read (a wrong shape
 * or a blank value is a stable `invalid arguments` failure, never a crash).
 */
internal fun strArg(
    args: JsonObject,
    key: String,
    maxLength: Int,
): String? =
    (args[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() && it.length <= maxLength }

/** Bounds a port error message for the model-visible `detail` (never the audit, which keeps it raw). */
internal fun bounded(detail: String): String {
    val t = detail.trim()
    return if (t.length <= MAX_DETAIL) t else t.take(MAX_DETAIL) + "…"
}

// ===========================================================================
// android.open_uri — open an http/https URL in the system handler (只打开)
// ===========================================================================

// ===========================================================================
// clipboard.read — read the system clipboard (gated by visible-foreground)
// ===========================================================================

// ===========================================================================
// clipboard.write — write text to the system clipboard (gated by visible-foreground)
// ===========================================================================

// ===========================================================================
// android.share — share text via the system chooser (分享输入先预览)
// ===========================================================================

// ===========================================================================
// Registration
// ===========================================================================
object AndroidSystemTools {
    /**
     * Registers the four `android.*` / `clipboard.*` contracts and implementations against the shared
     * [bridge]. Called once from the app container (which owns the production
     * [AndroidSystemBridgeImpl]); tests build a [ToolRegistry] / [ToolImplementationRegistry] pair and
     * a fake bridge.
     */
    fun registerAll(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        AndroidOpenUriTool.register(registry, implementations, bridge)
        ClipboardReadTool.register(registry, implementations, bridge)
        ClipboardWriteTool.register(registry, implementations, bridge)
        AndroidShareTool.register(registry, implementations, bridge)
    }
}
