package com.helix.tools.automation

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

internal class AndroidSnapshotNode(
    private val node: AccessibilityNodeInfo,
) : SnapshotNode {
    override val packageName: String?
        get() = node.packageName?.toString()
    override val windowId: Int
        get() = node.windowId
    override val className: String?
        get() = node.className?.toString()
    override val text: String?
        get() = node.text?.toString()
    override val contentDescription: String?
        get() = node.contentDescription?.toString()
    override val viewId: String?
        get() = node.viewIdResourceName
    override val bounds: AutomationNodeBounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return AutomationNodeBounds(rect.left, rect.top, rect.right, rect.bottom)
        }
    override val clickable: Boolean
        get() = node.isClickable
    override val longClickable: Boolean
        get() = node.isLongClickable
    override val editable: Boolean
        get() = node.isEditable
    override val scrollable: Boolean
        get() = node.isScrollable
    override val enabled: Boolean
        get() = node.isEnabled
    override val password: Boolean
        get() = node.isPassword
    override val accessibilityDataSensitive: Boolean
        get() = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive
    override val childCount: Int
        get() = node.childCount

    override fun childAt(index: Int): SnapshotNode? = node.getChild(index)?.let(::AndroidSnapshotNode)

    override fun performAction(
        action: Int,
        arguments: Bundle?,
    ): Boolean = node.performAction(action, arguments)

    @Suppress("DEPRECATION")
    override fun recycle() {
        node.recycle()
    }
}
