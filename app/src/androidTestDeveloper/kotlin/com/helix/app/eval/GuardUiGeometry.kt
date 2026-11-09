package com.helix.app.eval

import android.graphics.Rect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Finite, screen-local gesture validation for benchmark-only task guards. */
internal object GuardUiGeometry {
    fun withinBounds(
        input: JsonObject,
        bounds: Rect,
    ): Boolean {
        if (bounds.isEmpty) return false
        val strokes = input["strokes"]?.jsonArray.orEmpty()
        return strokes.isNotEmpty() &&
            strokes.all { stroke ->
                val points = stroke.jsonObject["points"]?.jsonArray.orEmpty()
                points.isNotEmpty() && points.all { point -> contains(bounds, point.jsonObject) }
            }
    }

    fun contains(
        bounds: Rect,
        point: JsonObject,
    ): Boolean {
        val x = point["x"]?.jsonPrimitive?.doubleOrNull
        val y = point["y"]?.jsonPrimitive?.doubleOrNull
        return x != null && y != null && x >= bounds.left && x < bounds.right &&
            y >= bounds.top && y < bounds.bottom
    }
}
