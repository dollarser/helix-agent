package com.helix.app.eval

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Explicit emulator-only comparison, outside the product session. Never a production fallback. */
internal object LongPressReferenceInput {
    fun openFixtureDirectory() {
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (clickFixtureDirectory(automation)) return
            SystemClock.sleep(100)
        }
        error("Test setup could not find the Podcasts directory")
    }

    private fun clickFixtureDirectory(automation: UiAutomation): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        return try {
            clickFixtureDirectory(root)
        } finally {
            root.recycle()
        }
    }

    private fun clickFixtureDirectory(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName.toString() != "com.google.android.documentsui") return false
        val matches = root.findAccessibilityNodeInfosByText("Podcasts")
        return try {
            val label = matches.singleOrNull { it.text?.toString() == "Podcasts" }
            if (label == null) {
                false
            } else {
                clickAncestor(label)
                true
            }
        } finally {
            matches.forEach { it.recycle() }
        }
    }

    private fun clickAncestor(label: AccessibilityNodeInfo) {
        var target = AccessibilityNodeInfo.obtain(label)
        try {
            while (!target.isClickable) {
                val parent = checkNotNull(target.parent)
                target.recycle()
                target = parent
            }
            check(target.packageName.toString() == "com.google.android.documentsui")
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            target.recycle()
        }
    }

    fun compare(
        x: Int,
        y: Int,
    ): JSONObject {
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        SystemClock.sleep(300)
        val report = JSONObject().put("before", nodes(automation))
        val trials = JSONArray()
        for (type in listOf(MotionEvent.TOOL_TYPE_UNKNOWN, MotionEvent.TOOL_TYPE_FINGER)) {
            check(
                automation.rootInActiveWindow?.let { root ->
                    try {
                        root.packageName.toString() == "com.google.android.documentsui"
                    } finally {
                        root.recycle()
                    }
                } == true,
            )
            val down = SystemClock.uptimeMillis()

            fun inject(action: Int): Boolean {
                val property =
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = type
                    }
                val coordinate =
                    MotionEvent.PointerCoords().apply {
                        this.x = x.toFloat()
                        this.y = y.toFloat()
                        pressure = 1f
                        size = 1f
                    }
                val event =
                    MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        1,
                        arrayOf(property),
                        arrayOf(coordinate),
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0,
                    )
                return try {
                    automation.injectInputEvent(event, true)
                } finally {
                    event.recycle()
                }
            }
            val accepted = inject(MotionEvent.ACTION_DOWN)
            val released: Boolean
            try {
                SystemClock.sleep(1800)
            } finally {
                released = inject(MotionEvent.ACTION_UP)
            }
            SystemClock.sleep(300)
            val after = nodes(automation)
            trials.put(
                JSONObject()
                    .put("toolType", type)
                    .put("downAccepted", accepted)
                    .put("upAccepted", released)
                    .put("after", after),
            )
            if ((0 until after.length()).any { after.getJSONObject(it).optString("text").contains("selected") }) {
                check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                SystemClock.sleep(300)
            }
        }
        return report.put("trials", trials)
    }

    private fun nodes(automation: UiAutomation): JSONArray {
        val output = JSONArray()

        fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
        ) {
            try {
                if (depth > 64 || output.length() >= 400) return
                output.put(
                    JSONObject()
                        .put("text", node.text?.toString())
                        .put("viewId", node.viewIdResourceName)
                        .put("longClickable", node.isLongClickable)
                        .put("actionLabels", JSONArray(node.actionList.map { it.label?.toString().orEmpty() }))
                        .put("actions", JSONArray(node.actionList.map { it.id })),
                )
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            } finally {
                node.recycle()
            }
        }
        automation.rootInActiveWindow?.let { visit(it, 0) }
        return output
    }
}
