package com.helix.app.eval

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Uses the last model-visible observation without refreshing or replacing its identity. */
internal object GuardBrightnessGeometry {
    private const val SLIDER_ID = "com.android.systemui:id/slider"

    fun gesturePoints(input: JsonObject): JsonArray? {
        val strokes = input["strokes"]?.jsonArray.orEmpty()
        if (strokes.size != 1) return null
        val points = strokes.single().jsonObject["points"]?.jsonArray
        return if (points == null || points.size < 2) null else points
    }

    fun verify(
        points: JsonArray,
        observed: JsonObject,
        ui: UiAutomation,
        refused: (String) -> Unit = {},
    ): Boolean {
        val rectangle = observedRectangle(observed)
        val reason =
            when {
                rectangle == null -> "BRIGHTNESS_OBSERVATION_INVALID"
                !horizontalPath(points, rectangle) -> "BRIGHTNESS_PATH_OUTSIDE_SLIDER"
                else -> liveSliderRefusal(observed, rectangle, ui)
            }
        reason?.let(refused)
        return reason == null
    }

    fun observedRectangle(observed: JsonObject): Rect? {
        if (observed["truncated"]?.jsonPrimitive?.content == "true") return null
        val slider = observed["nodes"]?.jsonArray?.map { it.jsonObject }?.singleOrNull(::isObservedSlider)
        val bounds = slider?.get("bounds")?.jsonObject
        return bounds?.let(::rectangle)?.takeUnless { it.isEmpty }
    }

    private fun rectangle(bounds: JsonObject): Rect? {
        val edges = listOf("left", "top", "right", "bottom").map { bounds[it]?.jsonPrimitive?.content?.toIntOrNull() }
        return if (edges.any { it == null }) {
            null
        } else {
            Rect(
                requireNotNull(edges[0]),
                requireNotNull(edges[1]),
                requireNotNull(edges[2]),
                requireNotNull(edges[3]),
            )
        }
    }

    private fun isObservedSlider(node: JsonObject): Boolean =
        node["viewId"]?.jsonPrimitive?.content == SLIDER_ID &&
            hasSliderCapabilities(node) && hasBrightnessLabel(node)

    private fun hasSliderCapabilities(node: JsonObject): Boolean =
        node["canSetProgress"]?.jsonPrimitive?.content == "true" &&
            node["enabled"]?.jsonPrimitive?.content == "true" &&
            node["redacted"]?.jsonPrimitive?.content != "true" &&
            node["offscreen"]?.jsonPrimitive?.content != "true"

    private fun hasBrightnessLabel(node: JsonObject): Boolean =
        listOf("text", "contentDescription").any { key ->
            node[key]
                ?.jsonPrimitive
                ?.content
                ?.lowercase()
                ?.contains("brightness") == true
        }

    private fun liveSliderRefusal(
        observed: JsonObject,
        rectangle: Rect,
        ui: UiAutomation,
    ): String? {
        val root = ui.rootInActiveWindow ?: return "BRIGHTNESS_LIVE_ROOT_UNAVAILABLE"
        return try {
            when {
                !liveWindowMatches(root, observed) -> "BRIGHTNESS_WINDOW_CHANGED"
                !liveControlMatches(root, rectangle) -> "BRIGHTNESS_CONTROL_CHANGED"
                else -> overlappingControlRefusal(root, rectangle)
            }
        } finally {
            root.recycle()
        }
    }

    private fun liveWindowMatches(
        root: AccessibilityNodeInfo,
        observed: JsonObject,
    ): Boolean {
        val windowId = observed["windowId"]?.jsonPrimitive?.content?.toIntOrNull()
        val differentWindow = windowId == null || root.windowId != windowId
        return root.packageName?.toString() == "com.android.systemui" && !differentWindow
    }

    private fun overlappingControlRefusal(
        root: AccessibilityNodeInfo,
        rectangle: Rect,
    ): String? {
        val scanner = SliderTreeScan(rectangle)
        val containsSlider = scanner.scan(root, 0)
        return when {
            !containsSlider -> "BRIGHTNESS_SLIDER_MISSING_FROM_TREE"
            scanner.overlappingControl != null -> "BRIGHTNESS_OVERLAPPING_CONTROL:${scanner.overlappingControl}"
            else -> null
        }
    }

    private fun liveControlMatches(
        root: AccessibilityNodeInfo,
        rectangle: Rect,
    ): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(SLIDER_ID)
        return try {
            nodes.singleOrNull()?.let { live -> matchesControl(live, rectangle) } == true
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun matchesControl(
        live: AccessibilityNodeInfo,
        rectangle: Rect,
    ): Boolean {
        val liveBounds = Rect()
        live.getBoundsInScreen(liveBounds)
        val label = "${live.text?.toString().orEmpty()} ${live.contentDescription?.toString().orEmpty()}".lowercase()
        val wrongGeometry = liveBounds != rectangle
        val wrongType = "brightness" !in label || live.className?.toString() != "android.widget.SeekBar"
        return live.isEnabled && live.isVisibleToUser && !wrongGeometry && !wrongType
    }

    fun horizontalPath(
        points: JsonArray,
        rectangle: Rect,
    ): Boolean {
        val firstY =
            points
                .first()
                .jsonObject["y"]
                ?.jsonPrimitive
                ?.doubleOrNull ?: return false
        return points.all { point ->
            val y = point.jsonObject["y"]?.jsonPrimitive?.doubleOrNull
            y == firstY && GuardUiGeometry.contains(rectangle, point.jsonObject)
        }
    }

    /** AOSP's behind scrim consumes background touches; it is not an actionable overlay.
     * This exception is deliberately limited to the isolated SystemUI brightness fixture.
     */
    internal fun isKnownBehindScrim(
        packageName: String?,
        viewId: String?,
        className: String?,
        bounds: Rect,
        slider: Rect,
        plainLeaf: Boolean,
    ): Boolean {
        val identity =
            packageName == "com.android.systemui" && viewId == "com.android.systemui:id/scrim_behind" &&
                className == "android.view.View"
        val coverage = !bounds.isEmpty && !slider.isEmpty && bounds.contains(slider)
        return identity && plainLeaf && coverage
    }

    private class SliderTreeScan(
        private val rectangle: Rect,
    ) {
        private var visited = 0
        var overlappingControl: String? = null
            private set

        fun scan(
            node: AccessibilityNodeInfo,
            depth: Int,
        ): Boolean {
            check(++visited <= 5000 && depth <= 100) { "Brightness tree exceeds bounded safety check" }
            var containsSlider = node.viewIdResourceName == SLIDER_ID
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child != null) {
                    try {
                        containsSlider = scan(child, depth + 1) || containsSlider
                    } finally {
                        child.recycle()
                    }
                }
            }
            if (!containsSlider && isOverlappingControl(node) && overlappingControl == null) {
                val viewId = node.viewIdResourceName.orEmpty().take(160)
                val className =
                    node.className
                        ?.toString()
                        .orEmpty()
                        .take(160)
                overlappingControl = "$viewId|$className"
            }
            return containsSlider
        }

        private fun isOverlappingControl(node: AccessibilityNodeInfo): Boolean {
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            val interactive = node.isClickable || node.isLongClickable || node.isCheckable
            val plainLeaf = node.childCount == 0 && !node.isLongClickable && !node.isCheckable
            val unlabeled = node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()
            val backgroundTouchSink =
                Build.VERSION.SDK_INT == 33 &&
                    isKnownBehindScrim(
                        node.packageName?.toString(),
                        node.viewIdResourceName,
                        node.className?.toString(),
                        nodeBounds,
                        rectangle,
                        plainLeaf && unlabeled,
                    )
            val interactiveAndVisible = node.isVisibleToUser && node.isEnabled && interactive
            return interactiveAndVisible && Rect.intersects(rectangle, nodeBounds) && !backgroundTouchSink
        }
    }
}
