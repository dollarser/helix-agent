package com.helix.app.eval

import android.graphics.Rect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Coordinates are admitted only inside the already-verified renderer or recorder rectangle. */
internal object BrowserGuardGeometry {
    fun gestureInsideBounds(
        input: JsonObject,
        bounds: Rect,
    ): Boolean {
        val strokes = input["strokes"]?.jsonArray ?: return false
        return strokes.isNotEmpty() &&
            strokes.all { stroke ->
                val points = stroke.jsonObject["points"]?.jsonArray.orEmpty()
                points.isNotEmpty() && points.all { point -> pointInsideBounds(point.jsonObject, bounds) }
            }
    }

    private fun pointInsideBounds(
        point: JsonObject,
        bounds: Rect,
    ): Boolean {
        val x = point["x"]?.jsonPrimitive?.doubleOrNull
        val y = point["y"]?.jsonPrimitive?.doubleOrNull
        return x != null && y != null && boundsContainPoint(bounds, x, y)
    }

    private fun boundsContainPoint(
        bounds: Rect,
        x: Double,
        y: Double,
    ): Boolean = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
}
