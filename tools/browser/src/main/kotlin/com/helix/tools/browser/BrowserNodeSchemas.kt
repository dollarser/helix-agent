package com.helix.tools.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Bounded per-node schema shared by snapshots and find results. */
internal fun nodeSchema(): JsonObject =
    objectSchema(
        properties =
            buildJsonObject {
                put("index", integerSchema("Position of the node in the snapshot (stable within it).", null, null))
                put(
                    "role",
                    stringSchema(MAX_ROLE, "Semantic role: link, button, field, image, heading or interactive."),
                )
                put("text", stringSchema(MAX_NODE_TEXT, "Bounded visible text (UNTRUSTED page data)."))
                put("value", stringSchema(MAX_NODE_TEXT, "Field value; empty for a password field (never read)."))
                put("href", stringSchema(MAX_NODE_TEXT, "Bounded href; empty when not applicable."))
                put("name", stringSchema(MAX_NODE_TEXT, "Bounded accessible name; empty when not applicable."))
                put("token", stringSchema(MAX_TOKEN, "Short-lived node token; the only handle for click / type."))
            },
        required = listOf("index", "role", "text", "value", "href", "name", "token"),
    )

internal fun nodeObject(n: BrowserNodeView): JsonObject =
    buildJsonObject {
        put("index", JsonPrimitive(n.index))
        put("role", JsonPrimitive(n.role))
        put("text", JsonPrimitive(n.text))
        put("value", JsonPrimitive(n.value))
        put("href", JsonPrimitive(n.href))
        put("name", JsonPrimitive(n.name))
        put("token", JsonPrimitive(n.token))
    }
