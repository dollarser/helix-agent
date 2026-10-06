package com.helix.extensions.mobileuse.automation

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.extensions.mobileuse.R

internal class AndroidSnapshotNode(
    private val node: AccessibilityNodeInfo,
    private val permissionController: String? = null,
    private val installer: String? = null,
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
    override val checkable: Boolean
        get() = node.isCheckable
    override val checked: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= 36) {
                node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
            } else {
                legacyChecked()
            }
    override val password: Boolean
        get() = node.isPassword
    override val accessibilityDataSensitive: Boolean
        get() =
            Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive &&
                !publicPermissionControl(permissionController, packageName, viewId, password, editable) &&
                !publicInstallerControl(installer, packageName, viewId, password, editable, className)
    override val range: AutomationNodeRange?
        get() = node.rangeInfo?.let { AutomationNodeRange(it.min, it.max, it.current) }
    override val canSetProgress: Boolean
        get() = node.actionList.any { it.id == android.R.id.accessibilityActionSetProgress }
    override val canImeEnter: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
            } else {
                false
            }
    override val childCount: Int
        get() = node.childCount

    override fun childAt(index: Int): SnapshotNode? =
        node.getChild(index)?.let {
            AndroidSnapshotNode(it, permissionController, installer)
        }

    override fun performAction(
        action: Int,
        arguments: Bundle?,
    ): Boolean = node.performAction(action, arguments)

    override fun setText(value: String): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
        )

    override fun imeEnter(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            false
        }

    override fun setProgress(value: Float): Boolean =
        node.performAction(
            android.R.id.accessibilityActionSetProgress,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) },
        )

    @Suppress("DEPRECATION")
    private fun legacyChecked(): Boolean = node.isChecked

    @Suppress("DEPRECATION")
    override fun recycle() {
        node.recycle()
    }
}
